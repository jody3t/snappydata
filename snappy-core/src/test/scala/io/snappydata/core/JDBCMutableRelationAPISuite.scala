package io.snappydata.core

import java.sql.{DriverManager, SQLException}

import org.scalatest.{FunSuite, BeforeAndAfter, BeforeAndAfterAll} //scalastyle:ignore

import org.apache.spark.sql.{SnappyContext, SaveMode}
import org.apache.spark.{Logging, SparkContext}

/**
  * Created by rishim on 27/8/15.
  */
class JDBCMutableRelationAPISuite extends FunSuite //scalastyle:ignore
with Logging with BeforeAndAfter with BeforeAndAfterAll {

  var sc: SparkContext = null

  val path = "target/JDBCMutableRelationAPISuite"

  override def afterAll(): Unit = {
    SnappyContext.stop()

    try {
      DriverManager.getConnection(s"jdbc:derby:$path;shutdown=true")
    } catch {
      // Throw if not normal single database shutdown
      // https://db.apache.org/derby/docs/10.2/ref/rrefexcept71493.html
      case sqlEx: SQLException =>
        if (sqlEx.getSQLState != "08006" && sqlEx.getSQLState != "XJ015") {
          throw sqlEx
        }
    }
    FileCleaner.cleanFile(path)
    FileCleaner.cleanStoreFiles()
  }

  override def beforeAll(): Unit = {
    sc = new LocalSQLContext().sparkContext
    DriverManager.getConnection(s"jdbc:derby:$path;create=true")
  }

  test("Create table in an external DataStore in Non-Embedded mode") {
    val snc = SnappyContext(sc)

    val props = Map(
      "url" -> s"jdbc:derby:$path",
      "driver" -> "org.apache.derby.jdbc.EmbeddedDriver",
      "poolImpl" -> "tomcat",
      "user" -> "app",
      "password" -> "app"
    )
    snc.sql("DROP TABLE IF EXISTS TEST_JDBC_TABLE_1")

    val data = Seq(Seq(1, 2, 3), Seq(7, 8, 9), Seq(9, 2, 3), Seq(4, 2, 3), Seq(5, 6, 7))
    val rdd = sc.parallelize(data, data.length).map(s => new Data(s(0), s(1), s(2)))
    val dataDF = snc.createDataFrame(rdd)
    dataDF.write.format("jdbc").mode(SaveMode.Overwrite)
        .options(props).saveAsTable("TEST_JDBC_TABLE_1")
    val count = dataDF.count()
    assert(count === data.length)
  }

}
