/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package org.apache.spark.memory

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

import scala.collection.mutable

import com.gemstone.gemfire.distributed.internal.DistributionConfig
import com.gemstone.gemfire.internal.cache.store.ManagedDirectBufferAllocator
import com.gemstone.gemfire.internal.shared.unsafe.UnsafeHolder
import com.gemstone.gemfire.internal.snappy.UMMMemoryTracker
import com.pivotal.gemfirexd.internal.engine.Misc
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap

import org.apache.spark.storage.BlockId
import org.apache.spark.util.Utils
import org.apache.spark.{Logging, SparkConf}

/**
  * When there is request for execution or storage memory, critical up and eviction up
  * events are checked. If they are set, try to free the memory cached by Spark rdds
  * by calling memoryStore.evictBlocksToFreeSpace. If enough memory cannot be freed,
  * return the call and let Spark take a corrective action.
  * In such cases Spark either fails the task or move the current RDDs data to disk.
  * If the critical and eviction events are not set, it asks the UnifiedMemoryManager
  * to allocate the space.
  *
  * @param conf
  * @param maxHeapMemory
  * @param numCores
  */
class SnappyUnifiedMemoryManager private[memory](
    conf: SparkConf,
    override val maxHeapMemory: Long,
    numCores: Int)
  extends UnifiedMemoryManager(SnappyUnifiedMemoryManager.setMemorySize(conf),
    maxHeapMemory,
    (maxHeapMemory * conf.getDouble("spark.memory.storageFraction", 0.5)).toLong,
    numCores) with StoreUnifiedManager {

  private val maxOffHeapStorageSize = (maxOffHeapMemory *
      conf.getDouble("spark.memory.storageMaxFraction", 0.9)).toLong

  /**
   * If total heap size is small enough then try and use explicit GC to
   * release pending off-heap references before failing storage allocation.
   */
  private val canUseExplicitGC = {
    // use explicit System.gc() only if total-heap size is not large
    maxOffHeapMemory > 0 && Runtime.getRuntime.totalMemory <=
        SnappyUnifiedMemoryManager.EXPLICIT_GC_LIMIT
  }

  private val onHeapStorageRegionSize = onHeapStorageMemoryPool.poolSize

  private val maxHeapStorageSize = (maxHeapMemory *
      conf.getDouble("spark.memory.storageMaxFraction", 0.9)).toLong
  // TODO: [sumedh] Not being used?
  val maxExecutionSize: Long = (maxHeapMemory * 0.75).toLong

  private val minHeapEviction = math.min(math.max(10L * 1024L * 1024L,
    (maxHeapStorageSize * 0.002).toLong), 1024L * 1024L * 1024L)

  @volatile private var _memoryForObjectMap: Object2LongOpenHashMap[String] = _

  private[memory] def memoryForObject: Object2LongOpenHashMap[String] = {
    val memoryMap = _memoryForObjectMap
    if (memoryMap eq null) synchronized {
      val memoryMap = _memoryForObjectMap
      if (memoryMap eq null) {
        _memoryForObjectMap = new Object2LongOpenHashMap[String]()
        _memoryForObjectMap.defaultReturnValue(0L)
        // transfer the memory map from tempMemoryManager on first use
        MemoryManagerCallback.tempMemoryManager.memoryForObject.map { entry =>
          val (objectName, mode) = entry._1
          acquireStorageMemoryForObject(objectName,
            MemoryManagerCallback.storageBlockId, entry._2, mode, null,
            shouldEvict = false)
        }
        MemoryManagerCallback.tempMemoryManager.memoryForObject.clear()
        _memoryForObjectMap
      } else memoryMap
    } else memoryMap
  }

  val threadsWaitingForStorage = new AtomicInteger()

  val SPARK_CACHE = "_SPARK_CACHE_"

  private[memory] val evictor = new SnappyStorageEvictor

  def this(conf: SparkConf, numCores: Int) = {
    this(conf,
      SnappyUnifiedMemoryManager.getMaxMemory(conf),
      numCores)
  }

  logMemoryConfiguration()

  private def logMemoryConfiguration(): Unit = {
    val memoryLog = new StringBuilder
    val separator = "\n\t\t"
    memoryLog.append("MemoryManager configuration:")
    memoryLog.append(separator).append("Total Usable Heap = ")
        .append(Utils.bytesToString(maxHeapMemory))
        .append(" (").append(maxHeapMemory).append(')')
    memoryLog.append(separator).append("Storage Pool = ")
        .append(Utils.bytesToString(onHeapStorageRegionSize))
        .append(" (").append(onHeapStorageRegionSize).append(')')
    val executionPoolSize = onHeapExecutionMemoryPool.poolSize
    memoryLog.append(separator).append("Execution Pool = ")
        .append(Utils.bytesToString(executionPoolSize))
        .append(" (").append(executionPoolSize).append(')')
    memoryLog.append(separator).append("Max Storage Pool Size = ")
        .append(Utils.bytesToString(maxHeapStorageSize))
        .append(" (").append(maxHeapStorageSize).append(')')
    if (hasOffHeap) {
      memoryLog.append(separator).append("OffHeap Size = ")
          .append(Utils.bytesToString(maxOffHeapMemory))
          .append(" (").append(maxOffHeapMemory).append(')')
      memoryLog.append(separator).append("OffHeap Storage Pool = ")
          .append(Utils.bytesToString(offHeapStorageMemory))
          .append(" (").append(offHeapStorageMemory).append(')')
      val offHeapExecutionPoolSize = offHeapExecutionMemoryPool.poolSize
      memoryLog.append(separator).append("OffHeap Execution Pool = ")
          .append(Utils.bytesToString(offHeapExecutionPoolSize))
          .append(" (").append(offHeapExecutionPoolSize).append(')')
      memoryLog.append(separator).append("OffHeap Max Storage Pool Size = ")
          .append(Utils.bytesToString(maxOffHeapStorageSize))
          .append(" (").append(maxOffHeapStorageSize).append(')')
    }
    logInfo(memoryLog.toString())
  }

  override def getStoragePoolMemoryUsed(
      memoryMode: MemoryMode): Long = memoryMode match {
    case MemoryMode.OFF_HEAP => offHeapStorageMemoryPool.memoryUsed
    case MemoryMode.ON_HEAP => onHeapStorageMemoryPool.memoryUsed
  }

  override def getStoragePoolSize(
      memoryMode: MemoryMode): Long = memoryMode match {
    case MemoryMode.OFF_HEAP => offHeapStorageMemoryPool.poolSize
    case MemoryMode.ON_HEAP => onHeapStorageMemoryPool.poolSize
  }

  override def getExecutionPoolUsedMemory(
      memoryMode: MemoryMode): Long = memoryMode match {
    case MemoryMode.OFF_HEAP => offHeapExecutionMemoryPool.memoryUsed
    case MemoryMode.ON_HEAP => onHeapExecutionMemoryPool.memoryUsed
  }

  override def getExecutionPoolSize(
      memoryMode: MemoryMode): Long = memoryMode match {
    case MemoryMode.OFF_HEAP => offHeapExecutionMemoryPool.poolSize
    case MemoryMode.ON_HEAP => onHeapExecutionMemoryPool.poolSize
  }

  override def getOffHeapMemory(objectName: String): Long = {
    if (maxOffHeapMemory > 0) memoryForObject.getLong(objectName)
    else 0L
  }

  override def hasOffHeap: Boolean = tungstenMemoryMode eq MemoryMode.OFF_HEAP

  override def logStats(): Unit = synchronized {
    val memoryLog = new StringBuilder
    val separator = "\n\t\t"
    memoryLog.append("MemoryManager stats:")
    memoryLog.append(separator).append("Storage Used = ")
        .append(onHeapStorageMemoryPool.memoryUsed)
        .append(" (size=").append(onHeapStorageMemoryPool.poolSize).append(')')
    memoryLog.append(separator).append("Execution Used = ")
        .append(onHeapExecutionMemoryPool.memoryUsed)
        .append(" (size=").append(onHeapExecutionMemoryPool.poolSize).append(')')
    if (hasOffHeap) {
      memoryLog.append(separator).append("OffHeap Size = ")
          .append(Utils.bytesToString(maxOffHeapMemory))
          .append(" (").append(maxOffHeapMemory).append(')')
      memoryLog.append(separator).append("OffHeap Storage Used = ")
          .append(offHeapStorageMemoryPool.memoryUsed)
          .append(" (size=").append(offHeapStorageMemoryPool.poolSize).append(')')
      memoryLog.append(separator).append("OffHeap Execution Pool = ")
          .append(offHeapExecutionMemoryPool.memoryUsed)
          .append(" (size=").append(offHeapExecutionMemoryPool.poolSize).append(')')
    }
    val memoryForObject = this.memoryForObject
    if (!memoryForObject.isEmpty) {
      memoryLog.append("\n\t").append("Objects:\n")
      val objects = memoryForObject.entrySet().iterator()
      while (objects.hasNext) {
        val o = objects.next()
        memoryLog.append(separator).append(o.getKey).append(" = ").append(o.getValue)
      }
    }
    logInfo(memoryLog.toString())
  }

  override def changeOffHeapOwnerToStorage(buffer: ByteBuffer,
      allowNonAllocator: Boolean): Unit = synchronized {
    val capacity = buffer.capacity()
    val totalSize = capacity + ManagedDirectBufferAllocator.DIRECT_OBJECT_OVERHEAD
    val toOwner = ManagedDirectBufferAllocator.DIRECT_STORE_OBJECT_OWNER
    val changeOwner = new Consumer[String] {
      override def accept(fromOwner: String): Unit = {
        if (fromOwner ne null) {
          // "from" was changed to "to"
          val prev = memoryForObject.addTo(fromOwner, -totalSize)
          if (prev >= totalSize) {
            memoryForObject.addTo(toOwner, totalSize)
          } else {
            // something went wrong with size accounting
            memoryForObject.addTo(fromOwner, totalSize)
            throw new IllegalStateException(
              s"Unexpected move of $totalSize bytes from owner $fromOwner size=$prev")
          }
        } else if (allowNonAllocator) {
          // add to storage pool
          if (!askStoragePool(toOwner, MemoryManagerCallback.storageBlockId,
            totalSize, MemoryMode.OFF_HEAP, shouldEvict = true)) {
            throw ManagedDirectBufferAllocator.instance().lowMemoryException(
              "changeToStorage", totalSize)
          }
        } else throw new IllegalStateException(
          s"ByteBuffer Cleaner does not match expected source $fromOwner")
      }
    }
    // change the owner to storage
    ManagedDirectBufferAllocator.instance().changeOwnerToStorage(buffer,
      capacity, changeOwner)
  }

  def tryExplicitGC(): Unit = {
    // check if explicit GC should be invoked
    if (canUseExplicitGC) {
      logInfo("Invoking explicit GC before failing storage allocation request")
      System.gc()
      System.runFinalization()
    }
    UnsafeHolder.releasePendingReferences()
  }

  private def getMinHeapEviction(required: Long): Long = {
    // evict at least 100 entries to reduce GC cycles
    val waitingThreads = threadsWaitingForStorage.get()
    math.max(required * waitingThreads, math.min(minHeapEviction,
      required * math.max(100L, waitingThreads + 1)))
  }

  private def getMinOffHeapEviction(required: Long): Long = {
    // off-heap calculations are precise so evict exactly as much as required
    (required * threadsWaitingForStorage.get()) - offHeapStorageMemoryPool.memoryFree
  }

  /**
    * This method is copied from Spark. In addition to evicting data from spark block manager,
    * this will also evict data from SnappyStore.
    *
    * Try to acquire up to `numBytes` of execution memory for the current task and return the
    * number of bytes obtained, or 0 if none can be allocated.
    *
    * This call may block until there is enough free memory in some situations, to make sure each
    * task has a chance to ramp up to at least 1 / 2N of the total memory pool (where N is the # of
    * active tasks) before it is forced to spill. This can happen if the number of tasks increase
    * but an older task had a lot of memory already.
    */
  override private[memory] def acquireExecutionMemory(
      numBytes: Long,
      taskAttemptId: Long,
      memoryMode: MemoryMode): Long = synchronized {
    logDebug(s"Acquiring [SNAP] memory for $taskAttemptId $numBytes")
    assertInvariants()
    assert(numBytes >= 0)
    val (executionPool, storagePool, storageRegionSize, maxMemory,
    minEviction) = memoryMode match {
      case MemoryMode.ON_HEAP => (
          onHeapExecutionMemoryPool,
          onHeapStorageMemoryPool,
          onHeapStorageRegionSize,
          maxHeapMemory,
          getMinHeapEviction(numBytes))
      case MemoryMode.OFF_HEAP => (
          offHeapExecutionMemoryPool,
          offHeapStorageMemoryPool,
          offHeapStorageMemory,
          maxOffHeapMemory,
          getMinOffHeapEviction(numBytes))
    }

    /**
      * Grow the execution pool by evicting cached blocks, thereby shrinking the storage pool.
      *
      * When acquiring memory for a task, the execution pool may need to make multiple
      * attempts. Each attempt must be able to evict storage in case another task jumps in
      * and caches a large block between the attempts. This is called once per attempt.
      */
    def maybeGrowExecutionPool(extraMemoryNeeded: Long): Unit = {
      val offHeap = memoryMode eq MemoryMode.OFF_HEAP
      if (extraMemoryNeeded > 0) {

        if (!offHeap && SnappyMemoryUtils.isCriticalUp(getStoragePoolMemoryUsed(
          MemoryMode.ON_HEAP) + getExecutionPoolUsedMemory(MemoryMode.ON_HEAP))) {
          logWarning(s"CRTICAL_UP event raised due to critical heap memory usage. " +
            s"No memory allocated to thread ${Thread.currentThread()}")
          return
        }

        // There is not enough free memory in the execution pool, so try to reclaim memory from
        // storage. We can reclaim any free memory from the storage pool. If the storage pool
        // has grown to become larger than `storageRegionSize`, we can evict blocks and reclaim
        // the memory that storage has borrowed from execution.
        val memoryReclaimableFromStorage = math.max(
          storagePool.memoryFree,
          storagePool.poolSize - storageRegionSize)
        if (memoryReclaimableFromStorage > 0) {
          // Only reclaim as much space as is necessary and available:
          val spaceToReclaim = storagePool.freeSpaceToShrinkPool(
            math.min(extraMemoryNeeded, memoryReclaimableFromStorage))

          val bytesEvictedFromStore = if (spaceToReclaim < extraMemoryNeeded) {
            val moreBytesRequired = extraMemoryNeeded - spaceToReclaim
            val evicted = evictor.evictRegionData(math.min(moreBytesRequired +
                minEviction, memoryReclaimableFromStorage), offHeap)
            if (offHeap) {
              UnsafeHolder.releasePendingReferences()
            }
            evicted
          } else {
            0L
          }
          if(storagePool.poolSize - (spaceToReclaim + bytesEvictedFromStore)
            >= storagePool.memoryUsed){
            // Some eviction might have increased the storage memory used which will
            // case some requirement failing
            // while decreasing pool size.
            storagePool.decrementPoolSize(spaceToReclaim + bytesEvictedFromStore)
            executionPool.incrementPoolSize(spaceToReclaim + bytesEvictedFromStore)
          }

        }
      }
    }

    /**
      * The size the execution pool would have after evicting storage memory.
      *
      * The execution memory pool divides this quantity among the active tasks evenly to cap
      * the execution memory allocation for each task. It is important to keep this greater
      * than the execution pool size, which doesn't take into account potential memory that
      * could be freed by evicting storage. Otherwise we may hit SPARK-12155.
      *
      * Additionally, this quantity should be kept below `maxMemory` to arbitrate fairness
      * in execution memory allocation across tasks, Otherwise, a task may occupy more than
      * its fair share of execution memory, mistakenly thinking that other tasks can acquire
      * the portion of storage memory that cannot be evicted.
      */
    def computeMaxExecutionPoolSize(): Long = {
      maxMemory - math.min(storagePool.memoryUsed, storageRegionSize)
    }

    executionPool.acquireMemory(
      numBytes, taskAttemptId, maybeGrowExecutionPool, computeMaxExecutionPoolSize)
  }


  override def acquireStorageMemory(
      blockId: BlockId,
      numBytes: Long,
      memoryMode: MemoryMode): Boolean = {
    acquireStorageMemoryForObject(SPARK_CACHE, blockId, numBytes, memoryMode, null,
      shouldEvict = true)
  }

  private def askStoragePool(objectName: String,
      blockId: BlockId,
      numBytes: Long,
      memoryMode: MemoryMode,
      shouldEvict: Boolean): Boolean = {
    threadsWaitingForStorage.incrementAndGet()
    try {
      askStoragePool_(objectName, blockId, numBytes, memoryMode, shouldEvict)
    } finally {
      threadsWaitingForStorage.decrementAndGet()
    }
  }

  private def askStoragePool_(objectName: String,
      blockId: BlockId,
      numBytes: Long,
      memoryMode: MemoryMode,
      shouldEvict: Boolean): Boolean = {
    synchronized {
      if (!shouldEvict) {
        SnappyUnifiedMemoryManager.
          invokeListenersOnPositiveMemoryIncreaseDueToEviction(objectName, numBytes)
      }
      assertInvariants()
      assert(numBytes >= 0)
      val (executionPool, storagePool, maxMemory, maxStorageSize,
      minEviction) = memoryMode match {
        case MemoryMode.ON_HEAP => (
            onHeapExecutionMemoryPool,
            onHeapStorageMemoryPool,
            maxOnHeapStorageMemory,
            maxHeapStorageSize,
            getMinHeapEviction(numBytes))
        case MemoryMode.OFF_HEAP => (
            offHeapExecutionMemoryPool,
            offHeapStorageMemoryPool,
            maxOffHeapMemory - offHeapExecutionMemoryPool.memoryUsed,
            maxOffHeapStorageSize,
            getMinOffHeapEviction(numBytes))
      }


      if (numBytes > maxMemory) {
        // Fail fast if the block simply won't fit
        logWarning(s"Will not store $blockId for $objectName as " +
          s"the required space ($numBytes bytes) exceeds our " +
            s"memory limit ($maxMemory bytes)")
        return false
      }
      // don't borrow from execution for off-heap if shouldEvict=false since it
      // will try clearing references before calling with shouldEvict=true again
      val offHeap = memoryMode eq MemoryMode.OFF_HEAP
      val offHeapNoEvict = !shouldEvict && offHeap
      if (numBytes > storagePool.memoryFree && !offHeapNoEvict) {
        // There is not enough free memory in the storage pool, so try to borrow free memory from
        // the execution pool.
        val memoryBorrowedFromExecution = Math.min(executionPool.memoryFree, numBytes)
        val actualBorrowedMemory =
          if (storagePool.poolSize + memoryBorrowedFromExecution > maxStorageSize) {
            maxStorageSize - storagePool.poolSize
          } else {
            memoryBorrowedFromExecution
          }
        executionPool.decrementPoolSize(actualBorrowedMemory)
        storagePool.incrementPoolSize(actualBorrowedMemory)
      }
      // First let spark try to free some memory
      val enoughMemory = storagePool.acquireMemory(blockId, numBytes)
      if (!enoughMemory) {

        // return immediately for OFF_HEAP with shouldEvict=false
        if (offHeapNoEvict) return false

        if (!offHeap && SnappyMemoryUtils.isCriticalUp(getStoragePoolMemoryUsed(
          MemoryMode.ON_HEAP) + getExecutionPoolUsedMemory(MemoryMode.ON_HEAP))) {
          logWarning(s"CRTICAL_UP event raised due to critical heap memory usage. " +
            s"No memory allocated to thread ${Thread.currentThread()}")
          return false
        }

        if (shouldEvict) {
          // Sufficient memory could not be freed. Time to evict from SnappyData store.
          // val requiredBytes = numBytes - storagePool.memoryFree
          // Evict data a little more than required based on waiting tasks
          val evicted = evictor.evictRegionData(minEviction, offHeap)
          if (SnappyUnifiedMemoryManager.testCallbacks.nonEmpty) {
            SnappyUnifiedMemoryManager.testCallbacks.foreach(
              _.onEviction(objectName, evicted))
          }
          if (offHeap) {
            UnsafeHolder.releasePendingReferences()
          }
        } else {
          return false
        }

        var couldEvictSomeData = storagePool.acquireMemory(blockId, numBytes)
        // for off-heap try harder before giving up since pending references
        // may be on heap (due to unexpected exceptions) that will go away on GC
        if (!couldEvictSomeData && offHeap) {
          tryExplicitGC()
          couldEvictSomeData = storagePool.acquireMemory(blockId, numBytes)
        }
        if (!couldEvictSomeData) {
          logWarning(s"Could not allocate memory for $blockId of " +
            s"$objectName size=$numBytes. Memory pool size " + storagePool.memoryUsed)
        } else {
          memoryForObject.addTo(objectName, numBytes)
          logDebug(s"Allocated memory for $blockId of " +
            s"$objectName. Memory pool size " + storagePool.memoryUsed)
        }
        couldEvictSomeData
      } else {
        memoryForObject.addTo(objectName, numBytes)
        enoughMemory
      }
    }
  }

  override def acquireStorageMemoryForObject(objectName: String,
      blockId: BlockId,
      numBytes: Long,
      memoryMode: MemoryMode,
      buffer: UMMMemoryTracker,
      shouldEvict: Boolean): Boolean = {
    logDebug(s"Acquiring [SNAP] memory for $objectName $numBytes $shouldEvict")
    if (buffer ne null) {
      if (buffer.freeMemory() > numBytes) {
        buffer.incMemoryUsed(numBytes)
        true
      } else {
        val predictedMemory = numBytes * buffer.getTotalOperationsExpected
        buffer.incAllocatedMemory(predictedMemory)
        val success = askStoragePool(objectName, blockId, predictedMemory, memoryMode, shouldEvict)
        buffer.setFirstAllocationObject(objectName)
        buffer.incMemoryUsed(numBytes)
        success
      }
    } else {
      askStoragePool(objectName, blockId, numBytes, memoryMode, shouldEvict)
    }
  }

  override def releaseStorageMemoryForObject(objectName: String,
                                             numBytes: Long,
                                             memoryMode: MemoryMode): Unit = synchronized {
    logDebug(s"releasing [SNAP] memory for $objectName $numBytes")
    if (memoryForObject.addTo(objectName, -numBytes) != 0L) {
      super.releaseStorageMemory(numBytes, memoryMode)
    } else {
      // objectName was not present
      memoryForObject.removeLong(objectName)
    }
  }

  override def releaseStorageMemory(numBytes: Long, memoryMode: MemoryMode): Unit = synchronized {
    releaseStorageMemoryForObject(SPARK_CACHE, numBytes, memoryMode)
    logDebug(s"releasing [SNAP] memory for $SPARK_CACHE $numBytes")
    if (memoryForObject.containsKey(SPARK_CACHE)) {
      memoryForObject.addTo(SPARK_CACHE, -numBytes)
      super.releaseStorageMemory(numBytes, memoryMode)
    }
  }

  override def dropStorageMemoryForObject(name: String,
                                          memoryMode: MemoryMode,
                                          ignoreNumBytes: Long): Long = synchronized {
    logDebug(s"Dropping memory for $name")
    val bytesToBeFreed = memoryForObject.getLong(name)
    val numBytes = Math.max(0, bytesToBeFreed - ignoreNumBytes)

    if (numBytes > 0) {
      super.releaseStorageMemory(numBytes, memoryMode)
      memoryForObject.removeLong(name)
    }
    bytesToBeFreed
  }

  // Test Hook. Not to be used anywhere else
  private[memory] def dropAllObjects(memoryMode: MemoryMode): Unit = synchronized {
    val allValues = memoryForObject.values().iterator()
    while (allValues.hasNext) {
      super.releaseStorageMemory(allValues.nextLong(), memoryMode)
    }
    memoryForObject.clear()
  }
}

object SnappyUnifiedMemoryManager extends Logging {

  // Reserving minimum 500MB data for unaccounted data, GC headroom etc
  private val RESERVED_SYSTEM_MEMORY_BYTES = {
    // reserve 5% of heap by default subject to max of 5GB and min of 500MB
    math.min(5L * 1024L * 1024L * 1024L,
      math.max(getMaxHeapMemory / 20, 500L * 1024L * 1024L))
  }

  private def getMaxHeapMemory: Long = {
    val maxMemory = Runtime.getRuntime.maxMemory()
    if (maxMemory > 0 && maxMemory != Long.MaxValue) maxMemory
    else Runtime.getRuntime.totalMemory()
  }

  /**
   * The maximum limit of heap size till which an explicit GC will be
   * considered for invocation before failing a direct buffer allocation
   * request for the case when too many references are lying around uncollected.
   */
  private val EXPLICIT_GC_LIMIT = 10L * 1024 * 1024 * 1024

  private val testCallbacks = mutable.ArrayBuffer.empty[MemoryEventListener]

  def addMemoryEventListener(listener: MemoryEventListener): Unit = {
    testCallbacks += listener
  }

  def clearMemoryEventListener(): Unit = {
    testCallbacks.clear()
  }

  private def invokeListenersOnPositiveMemoryIncreaseDueToEviction(objectName: String,
                                                                   bytes: Long): Unit = {
    if (testCallbacks.nonEmpty) {
      testCallbacks.foreach(_.onPositiveMemoryIncreaseDueToEviction(objectName, bytes))
    }
  }

  /**
   * Check for SnappyData off-heap configuration and set Spark's properties.
   */
  def setMemorySize(conf: SparkConf): SparkConf = {
    val cache = Misc.getGemFireCacheNoThrow
    val memorySize = if (cache ne null) {
      cache.getMemorySize
    } else { // for local mode testing
      conf.getSizeAsBytes(DistributionConfig.SNAPPY_PREFIX +
          DistributionConfig.MEMORY_SIZE_NAME, "0b")
    }
    if (memorySize > 0) {
      // set Spark's off-heap properties
      conf.set("spark.memory.offHeap.enabled", "true")
      conf.set("spark.memory.offHeap.size", s"${memorySize}b")
    }
    conf
  }

  /**
    * Return the total amount of memory shared between execution and storage, in bytes.
    * This is a direct copy from UnifiedMemorymanager with an extra check for evit fraction
    */
  private def getMaxMemory(conf: SparkConf): Long = {
    var systemMemory = conf.getLong("spark.testing.memory", getMaxHeapMemory)
    // align reserved memory with critical heap size of GemFire
    val cache = Misc.getGemFireCacheNoThrow
    var reservedMemory = if (cache ne null) {
      val thresholds = cache.getResourceManager.getHeapMonitor.getThresholds
      if (thresholds.getCriticalThreshold > 0.1f) {
        systemMemory = thresholds.getMaxMemoryBytes
        // add a 30% cushion for GC before CRITICAL_UP is reached
        ((systemMemory - thresholds.getCriticalThresholdBytes) * 1.3).toLong
      } else RESERVED_SYSTEM_MEMORY_BYTES
    } else RESERVED_SYSTEM_MEMORY_BYTES
    reservedMemory = conf.getLong("spark.testing.reservedMemory",
      if (conf.contains("spark.testing")) 0 else reservedMemory)
    val minSystemMemory = (reservedMemory * 1.5).ceil.toLong
    if (systemMemory < minSystemMemory) {
      throw new IllegalArgumentException(s"System memory $systemMemory must " +
        s"be at least $minSystemMemory. Please increase heap size using the --driver-memory " +
        s"option or spark.driver.memory in Spark configuration.")
    }
    // SPARK-12759 Check executor memory to fail fast if memory is insufficient
    if (conf.contains("spark.executor.memory")) {
      val executorMemory = conf.getSizeAsBytes("spark.executor.memory")
      if (executorMemory < minSystemMemory) {
        throw new IllegalArgumentException(s"Executor memory $executorMemory must be at least " +
          s"$minSystemMemory. Please increase executor memory using the " +
          s"--executor-memory option or spark.executor.memory in Spark configuration.")
      }
    }

    val usableMemory = systemMemory - reservedMemory
    val memoryFraction = conf.getDouble("spark.memory.fraction", 1.0)
    (usableMemory * memoryFraction).toLong
  }
}

// Test listeners. Should not be used in production code.
abstract class MemoryEventListener {
  def onStorageMemoryAcquireSuccess(objectName : String, bytes : Long) : Unit = {}
  def onStorageMemoryAcquireFailure(objectName : String, bytes : Long) : Unit = {}
  def onEviction(objectName: String, evicted: Long): Unit = {}
  def onPositiveMemoryIncreaseDueToEviction(objectName : String, bytes : Long) : Unit = {}
  def onExecutionMemoryAcquireSuccess(taskAttemptId : Long, bytes : Long) : Unit = {}
  def onExecutionMemoryAcquireFailure(taskAttemptId : Long, bytes : Long) : Unit = {}
}
