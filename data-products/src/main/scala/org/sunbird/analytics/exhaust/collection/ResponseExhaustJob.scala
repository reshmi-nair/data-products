package org.sunbird.analytics.exhaust.collection

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.SparkSession
import org.ekstep.analytics.framework.FrameworkContext
import org.ekstep.analytics.framework.JobConfig
import org.ekstep.analytics.framework.conf.AppConf
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.functions._
import org.ekstep.analytics.framework.util.JSONUtils

object ResponseExhaustJob extends optional.Application with BaseCollectionExhaustJob {
  
  override def getClassName = "org.sunbird.analytics.exhaust.collection.ResponseExhaustJob"
  override def jobName() = "ResponseExhaustJob";
  override def jobId() = "response-exhaust";
  override def getReportPath() = "response-exhaust/";
  override def getReportKey() = "response";
  
  private val assessmentAggDBSettings = Map("table" -> "assessment_aggregator", "keyspace" -> AppConf.getConfig("sunbird.courses.keyspace"), "cluster" -> "LMSCluster");
  
  private val filterColumns = Seq("courseid", "collectionName", "batchid", "batchName", "userid", "username",  "content_id", "contentname", "attempt_id", "last_attempted_on", "questionid", 
      "questiontype", "questiontitle", "questiondescription", "questionduration", "questionscore", "questionmaxscore", "questionoption", "questionresponse");
  private val columnsOrder = List("Collection Id", "Collection Name", "Batch Id", "Batch Name", "User UUID", "User Name", "QuestionSet Id", "QuestionSet Title", "Attempt Id", "Attempted On", 
      "Question Id", "Question Type", "Question Title", "Question Description", "Question Duration", "Question Score", "Question Max Score", "Question Options", "Question Response");
  val columnMapping = Map("courseid" -> "Collection Id", "collectionName" -> "Collection Name", "batchid" -> "Batch Id", "batchName" -> "Batch Name", "userid" -> "User UUID", "username" -> "User Name", 
      "content_id" -> "QuestionSet Id", "contentname" -> "QuestionSet Title", "attempt_id" -> "Attempt Id", "last_attempted_on" -> "Attempted On", "questionid" -> "Question Id", 
      "questiontype" -> "Question Type", "questiontitle" -> "Question Title", "questiondescription" -> "Question Description", "questionduration" -> "Question Duration", 
      "questionscore" -> "Question Score", "questionmaxscore" -> "Question Max Score", "questionoption" -> "Question Options", "questionresponse" -> "Question Response")

  override def processBatch(userEnrolmentDF: DataFrame, collectionBatch: CollectionBatch)(implicit spark: SparkSession, fc: FrameworkContext, config: JobConfig): DataFrame = {
    
    val assessmentDF = getAssessmentDF(collectionBatch);
    val contentIds = assessmentDF.select("content_id").dropDuplicates().collect().map(f => f.get(0));
    val contentDF = searchContent(Map("request" -> Map("filters" -> Map("identifier" -> contentIds)))).withColumnRenamed("collectionName", "contentname").select("identifier", "contentname");
    val reportDF = assessmentDF.join(contentDF, assessmentDF("content_id") === contentDF("identifier"), "left_outer").join(userEnrolmentDF, Seq("courseid", "batchid", "userid"), "left_outer").drop("identifier").select(filterColumns.head, filterColumns.tail: _*);
    organizeDF(reportDF, columnMapping, columnsOrder);
  }
  
  def getAssessmentDF(batch: CollectionBatch)(implicit spark: SparkSession, fc: FrameworkContext, config: JobConfig): DataFrame = {
    loadData(assessmentAggDBSettings, cassandraFormat, new StructType()).where(col("course_id") === batch.collectionId && col("batch_id") === batch.batchId)
      .withColumnRenamed("user_id", "userid")
      .withColumnRenamed("batch_id", "batchid")
      .withColumnRenamed("course_id", "courseid")
      .withColumn("questiondata",explode_outer(col("question")) )
      .withColumn("questionid" , col("questiondata.id"))
      .withColumn("questiontype", col("questiondata.type"))
      .withColumn("questiontitle", col("questiondata.title"))
      .withColumn("questiondescription", col("questiondata.description"))
      .withColumn("questionduration", round(col("questiondata.duration")))
      .withColumn("questionscore", col("questiondata.score"))
      .withColumn("questionmaxscore", col("questiondata.max_score"))
      .withColumn("questionresponse", JSONParseUDF.toJSON(col("questiondata.resvalues")))
      .withColumn("questionoption", JSONParseUDF.toJSON(col("questiondata.params")))
      .drop("question", "questiondata")
  }
  
}

object JSONParseUDF extends Serializable {
  def toJSONFun(array: AnyRef): String = {
    val str = JSONUtils.serialize(array);
    val sanitizedStr = str.replace("\\n", "").replace("\\", "").replace("\"", "'");
    sanitizedStr;
  }

  val toJSON = udf[String, AnyRef](toJSONFun)
}