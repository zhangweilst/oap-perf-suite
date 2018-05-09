/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql

import com.databricks.spark.sql.perf.tpcds.Tables
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, FileUtil, Path}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.functions._
import org.apache.spark.util.Utils

import scala.collection.mutable

object OapBenchmarkDataBuilder extends OapPerfSuiteContext with Logging {

  private val defaultProperties = Map(
    "oap.benchmark.compression.codec"     -> "gzip",
    "oap.benchmark.support.oap.version"   -> "0.4.0",
    "oap.benchmark.tpcds.tool.dir"        -> "/home/oap/tpcds-kit/tools",
    "oap.benchmark.hdfs.file.root.dir"    -> "/user/oap/oaptest/",
    "oap.benchmark.database.prefix"       -> "",
    "oap.benchmark.database.postfix"      -> "",
    "oap.benchmark.tpcds.data.scale"      -> "200",
    "oap.benchmark.tpcds.data.partition"  -> "80"
  )

  private val _resultMap: mutable.LinkedHashMap[String, Seq[(String, Array[String])]] =
    new mutable.LinkedHashMap[String, Seq[(String, Array[String])]]

  def getDatabase(format: String) : String = {
    val prefix = properties.get("oap.benchmark.database.prefix").get
    val postfix = properties.get("oap.benchmark.database.postfix").get
    val dataScale = properties.get("oap.benchmark.tpcds.data.scale").get.toInt
    val baseName = format match {
      case "oap" => s"oap_tpcds_$dataScale"
      case "parquet" => s"parquet_tpcds_$dataScale"
      case _ => "default"
    }

    prefix + baseName + postfix
  }

  def formatTableLocation(rootDir: String, versionNum: String, tableFormat: String): String = {
    s"${rootDir}/${versionNum}/tpcds/${getDatabase(tableFormat)}/"
  }

  private val properties = {
    try {
      new mutable.HashMap[String, String]() ++=
        Utils.getPropertiesFromFile("./conf/oap-benchmark-default.conf")
    } catch {
      case e: IllegalArgumentException => {
        logWarning(e.getMessage + ". Use default setting!")
        defaultProperties
      }
    }
  }

  override def beforeAll(conf: Map[String, String] = Map.empty): Unit = {
    super.beforeAll(conf)
  }

  def generateTables(dataFormats: Array[String] = Array("oap", "parquet")): Unit = {
    val versionNum = properties.get("oap.benchmark.support.oap.version").get
    val codec = properties.get("oap.benchmark.compression.codec").get
    val scale = properties.get("oap.benchmark.tpcds.data.scale").get.toInt
    val partitions = properties.get("oap.benchmark.tpcds.data.partition").get.toInt
    val hdfsRootDir = properties.get("oap.benchmark.hdfs.file.root.dir").get
    val tpcdsToolPath = properties.get("oap.benchmark.tpcds.tool.dir").get

    dataFormats.foreach{ format =>
      sqlContext.setConf(s"spark.sql.$format.compression.codec", codec)
      val loc = formatTableLocation(hdfsRootDir, versionNum, format)
      val tables = new Tables(sqlContext, tpcdsToolPath, scale)
      tables.genData(
        loc, format, true, false, true, false, false, "store_sales", partitions)
    }
  }

  def generateDatabases() {
    // TODO: get from OapFileFormatConfigSet
    val dataFormats: Seq[String] = Seq("oap", "parquet")
    dataFormats.foreach { format =>
      spark.sql(s"create database if not exists ${getDatabase(format)}")
    }

    def genData(dataFormat: String) = {
      val versionNum = properties.get("oap.benchmark.support.oap.version").get
      val hdfsRootDir = properties.get("oap.benchmark.hdfs.file.root.dir").get
      val dataLocation = formatTableLocation(hdfsRootDir, versionNum, dataFormat)

      spark.sql(s"use ${getDatabase(dataFormat)}")
      spark.sql("drop table if exists store_sales")
      spark.sql("drop table if exists store_sales_dup")

      /**
       * To compare performance between B-Tree and Bitmap index, we generate duplicate
       * tables of store_sales here. Besides, store_sales_dup table can be used in testing
       * OAP strategies.
       */
      val df = spark.read.format(dataFormat).load(dataLocation + "store_sales")
      val divRatio = df.select("ss_item_sk").orderBy(desc("ss_item_sk")).limit(1).
        collect()(0)(0).asInstanceOf[Int] / 1000
      val divideUdf = udf((s: Int) => s / divRatio)
      df.withColumn("ss_item_sk1", divideUdf(col("ss_item_sk"))).write.format(dataFormat)
        .mode(SaveMode.Overwrite).save(dataLocation + "store_sales1")

      val conf = new Configuration()
      val hadoopFs = FileSystem.get(conf)
      hadoopFs.delete(new Path(dataLocation + "store_sales"), true)

      // Notice here delete source flag should firstly be set to false
      FileUtil.copy(hadoopFs, new Path(dataLocation + "store_sales1"),
        hadoopFs, new Path(dataLocation + "store_sales"), false, conf)
      FileUtil.copy(hadoopFs, new Path(dataLocation + "store_sales1"),
        hadoopFs, new Path(dataLocation + "store_sales_dup"), true, conf)

      sqlContext.createExternalTable("store_sales", dataLocation + "store_sales", dataFormat)
      sqlContext.createExternalTable("store_sales_dup", dataLocation + "store_sales_dup", dataFormat)
      logWarning(s"File size of original table store_sales in $dataFormats format: " +
        TestUtil.calculateFileSize("store_sales", dataLocation, dataFormat)
      )
      logWarning("Records of table store_sales: " +
        spark.read.format(dataFormat).load(dataLocation + "store_sales").count()
      )
    }

    dataFormats.foreach(genData)
  }

  def buildAllIndex() {
    def buildBtreeIndex(tablePath: String, table: String, attr: String): (String, Array[String]) = {
      try {
        spark.sql(s"DROP OINDEX ${table}_${attr}_index ON $table")
      } catch {
        case _: Throwable => logWarning("Index doesn't exist, so don't need to drop here!")
      }
      val indexTime = TestUtil.queryTime(
        spark.sql(
          s"CREATE OINDEX IF NOT EXISTS ${table}_${attr}_index ON $table ($attr) USING BTREE"
        ))
      val indexSize = TestUtil.calculateIndexSize(table, tablePath, attr)
      ("Btree", Array(indexTime.toString, indexSize))
    }

    def buildBitmapIndex(tablePath: String, table: String, attr: String): (String, Array[String]) = {
      try {
        spark.sql(s"DROP OINDEX ${table}_${attr}_index ON $table")
      } catch {
        case _: Throwable => logWarning("Index doesn't exist, so don't need to drop here!")
      }
      val indexTime = TestUtil.queryTime(
        spark.sql(
          s"CREATE OINDEX IF NOT EXISTS ${table}_${attr}_index ON $table ($attr) USING BITMAP"
        ))
      val indexSize = TestUtil.calculateIndexSize(table, tablePath, attr)
      ("Bitmap", Array(indexTime.toString, indexSize))
    }

    val versionNum = properties.get("oap.benchmark.support.oap.version").get
    val hdfsRootDir = properties.get("oap.benchmark.hdfs.file.root.dir").get
    val dataFormats: Seq[String] = Seq("oap", "parquet")

    dataFormats.foreach { dataFormat => {
        val test = dataFormat + " index cost"
        spark.sql(s"use ${getDatabase(dataFormat)}")
        val tableLocation: String = formatTableLocation(hdfsRootDir, versionNum, dataFormat)
        val btreeCost = buildBtreeIndex(tableLocation, "store_sales", "ss_customer_sk")
        val bitmapCost = buildBitmapIndex(tableLocation, "store_sales", "ss_item_sk1")
        _resultMap.put(test, Seq(btreeCost, bitmapCost))
      }
    }

    /**
      * We output the index cost(index construction time and index size) here.
      * The output format refers to that of [[OapTestSuite]],
      * only that the repCount here is normally 1 instead of N
      */
    println("#" + this.getClass.getCanonicalName.dropRight(1))
    TestUtil.formatIndexResults(_resultMap.toSeq)
  }
}
