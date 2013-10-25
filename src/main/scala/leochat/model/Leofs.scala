package leochat.model

import java.io.ByteArrayInputStream
import java.util.Date
import scala.collection.JavaConversions.asScalaBuffer
import scala.io.Source
import scala.util.control.NonFatal
import scala.annotation.tailrec
import com.amazonaws.{ClientConfiguration, Protocol}
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{Bucket, GetObjectRequest, ListObjectsRequest, ObjectMetadata, PutObjectRequest}
import xitrum.Logger
import xitrum.util.{SeriDeseri, Loader, Json}


case class Msg(key: String, date: String, name: String, body: String, prevMsgKey: String)

object LeoFS extends Logger {
  private val leofsConfig = xitrum.Config.application.getConfig("leofs")
  private val LATEST = "latest"

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

    val prevMsgKey = getLatestKey().getOrElse("")

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

    val msg   = Msg(key, date, name, data, prevMsgKey)

    // TODO : Check why become error when deserialize
    // Caused by: com.esotericsoftware.kryo.KryoException: Buffer too small: capacity: 0, required: 1
    //    val bytes = SeriDeseri.serialize(msg)
    //    meta.setContentLength(bytes.length)

    val jsonStr = Json.generate(msg)
    meta.setContentLength(jsonStr.length)

    try {
      // s3.putObject(new PutObjectRequest(bucket.getName, key, new ByteArrayInputStream(bytes), meta))
      s3.putObject(new PutObjectRequest(bucket.getName, key, stringToStream(jsonStr), meta))
      saveLatest(key)
      Some(msg)
    } catch {
      case NonFatal(e) =>
        logger.warn("save error: " + e)
        None
    }
  }

  def saveLatest(key: String) = {
    val meta        = new ObjectMetadata
    meta.setContentLength(key.length)
    try {

      s3.putObject(new PutObjectRequest(bucket.getName, LATEST, stringToStream(key), meta))
    } catch {
      case NonFatal(e) =>
        logger.warn("save latest error: " + e)
        None
    }
  }

  def getLatestKey(): Option[String] = {
    try {
      val v = s3.getObject(new GetObjectRequest(bucket.getName, LATEST))
      val content = v.getObjectContent
      Some(Source.fromInputStream(content).getLines.mkString(""))
    } catch {
      case NonFatal(e) =>
        logger.warn("read latest error: " + e)
        None
    }
  }

  def read(key: String): Option[Msg] = {
    try {
      val v = s3.getObject(new GetObjectRequest(bucket.getName, key))

      // TODO : Check why become error when deserialize
      // Caused by: com.esotericsoftware.kryo.KryoException: Buffer too small: capacity: 0, required: 1
      // val inputStream = v.getObjectContent
      // val bytes = Loader.bytesFromInputStream(inputStream)
      // Some(SeriDeseri.deserialize(bytes).asInstanceOf[Msg])

      val content = v.getObjectContent
      val jsonText = Source.fromInputStream(content).getLines.mkString("")
      val msg = Json.parse[Msg](jsonText)
      Some(msg)
    } catch {
      case NonFatal(e) =>
        logger.warn("read error: " + e)
        None
    }
  }

  @tailrec
  def readAndPrev (num: Int, key: String, msgs: Seq[Msg]): Seq[Msg] = {
    if(num < 1) return msgs
    read(key) match {
      case Some(m) =>
        val msg = m.asInstanceOf[Msg]
        readAndPrev(num - 1, msg.prevMsgKey, msgs :+ msg)
      case None =>
        msgs
    }
  }

  def readHead(num: Int): Seq[Msg] = {
    getLatestKey() match {
      case Some(key) =>
        readAndPrev(num, key, Seq())
      case None =>  Seq()
    }
  }

  def readWithMarker(key: String, num: Int): Seq[Msg] = {
      readAndPrev(num+1, key, Seq()).tail
  }

  @Deprecated
  def readHeadWithObjectListing(num: Int): Seq[Msg] = {
    try {
      val r = new ListObjectsRequest
      r.setBucketName(bucket.getName)
      // r.setMaxKeys(num)
      var messages = Seq[Msg]()
      val objectListing = s3.listObjects(r)
      // objectListing.getObjectSummaries.toList.foreach { meta =>
      objectListing.getObjectSummaries.toList.take(num).foreach { meta =>
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

  @Deprecated
  def readWithMarkerWithObjectListing(key: String, num: Int): Seq[Msg] = {
    try {
      val r = new ListObjectsRequest
      r.setBucketName(bucket.getName)
      r.setMaxKeys(num + 1)
      r.setMarker(bucket.getName + "/" + key)

      var messages = Seq[Msg]()
      val objectListing = s3.listObjects(r)
      objectListing.getObjectSummaries.toList.foreach { meta =>
        // Display older to newer
        read(meta.getKey).get match {
          case msg:Msg => messages = messages :+ msg
          case ignore =>
        }
      }
      // First element is indicated by "key"
      messages.tail
    } catch {
      case NonFatal(e) =>
        logger.warn("readHead error: " + e)
        Seq()
    }
  }
}
