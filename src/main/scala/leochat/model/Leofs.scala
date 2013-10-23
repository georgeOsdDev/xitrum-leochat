package leochat.model

import java.io.ByteArrayInputStream
import java.util.Date

import scala.collection.JavaConversions.asScalaBuffer
import scala.io.Source
import scala.util.control.NonFatal

import com.amazonaws.{ClientConfiguration, Protocol}
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{Bucket, GetObjectRequest, ListObjectsRequest, ObjectMetadata, PutObjectRequest}

import xitrum.Logger
import xitrum.util.Json

case class Msg(key: String, date: String, name: String, body: String)

object LeoFS extends Logger {
  private val leofsConfig = xitrum.Config.application.getConfig("leofs")

  private val s3 = {
    // You need to set 'Proxy host', 'Proxy port' and 'Protocol'
    val config = new ClientConfiguration
    config.withProtocol(Protocol.HTTP)
    config.setProxyHost(leofsConfig.getString("proxyhost"))
    config.setProxyPort(leofsConfig.getInt("proxyport"))

    val accessKeyId     = leofsConfig.getString("accessKeyId")
    val secretAccessKey = leofsConfig.getString("secretAccessKey")

    val credentials = new BasicAWSCredentials(accessKeyId, secretAccessKey)
    new AmazonS3Client(credentials, config)
  }

  private def lookupBucket(name: String): Option[Bucket] = {
    val buckets = s3.listBuckets()
    buckets.toList.find { b => b.getName == name }
  }

  private var bucket: Bucket = {
    lookupBucket(leofsConfig.getString("bucket")).getOrElse {
      try {
        s3.createBucket(leofsConfig.getString("bucket"))
      } catch {
        case NonFatal(e) =>
          logger.error("Could not create bucket", e)
          throw e
          null
      }
    }
  }

  private def stringToStream(data: String) = {
    new ByteArrayInputStream(data.getBytes("UTF-8"))
  }

  def save(data: String, name: String): Option[Msg] = {
    // s3.listObjects() they are returned in alphabetical order (string comparison!)
    val length = Long.MaxValue.toString.length
    val key    = s"%${length}d".format(Long.MaxValue - System.currentTimeMillis())
    val date    =  "%tY/%<tm/%<td %<tH:%<tM:%<tS".format(new Date)

    val meta        = new ObjectMetadata

    // val userMetaData = Map(
    //    "leochat_content_type" -> "image",
    //    "leochat_date" -> date,
    //    "leochat_name" -> name
    // )
    // meta.setUserMetadata(userMetaData)
    // Could not get userMetaData in read(), so save as part of data
    val msg = Msg(key, date, name, data)
    val jsonStr = Json.generate(msg)
    meta.setContentLength(jsonStr.length)
    try {
      s3.putObject(new PutObjectRequest(bucket.getName, key, stringToStream(jsonStr), meta))
      Some(msg)
    } catch {
      case NonFatal(e) =>
        logger.warn("save error: " + e)
        None
    }
  }

  def read(key: String): Option[Msg] = {
    try {
      val v = s3.getObject(new GetObjectRequest(bucket.getName, key))
      val content = v.getObjectContent

      // val meta    = v.getObjectMetadata
      // val udata   = meta.getUserMetadata
      // Could not get userMetaData from storage why?
      val jsonText = Source.fromInputStream(content).getLines.mkString("")
      val msg = Json.parse[Msg](jsonText)
      Some(msg)
    } catch {
      case NonFatal(e) =>
        logger.warn("read error: " + e)
        None
    }
  }

  def readHead(num: Int): Seq[Msg] = {
    try {
      val r = new ListObjectsRequest
      r.setBucketName(bucket.getName)
      r.setMaxKeys(num)

      var messages = Seq[Msg]()
      val objectListing = s3.listObjects(r)
      objectListing.getObjectSummaries.toList.foreach { meta =>
        // Display older to newer
        read(meta.getKey).get match {
          case msg:Msg => messages = msg +: messages
          case ignore =>
        }
      }
      messages
    } catch {
      case NonFatal(e) =>
        logger.warn("readHead error: " + e)
        Seq()
    }
  }
}
