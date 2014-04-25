package water.sparkling.demo

import water.{DRemoteTask, Futures, H2O, Boot}
import org.apache.spark.{SparkContext, Partition, SparkConf}
import org.apache.spark.sql.SQLContext
import water.fvec.{NewChunk, AppendableVec, Vec, Frame}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.ScalaReflection
import org.apache.spark.sql.catalyst.expressions.Attribute
import scala.reflect.runtime.universe.TypeTag
import water.fvec.Vec.VectorGroup
import org.apache.spark.sql.catalyst.types._
import scala.Product
import scala.reflect.ClassTag

/**
 * This demo shows how to access data stored in Spark and transfer them
 * into H2O cloud.
 */
object SparklingDemo {

  /** Name of application */
  def APP_NAME = "Sparkling Demo"

  /** Main launch H2O bootloader and then switch to H2O classloader, and redirects the execution
    * to given class.
    */
  def main(args: Array[String]):Unit = {
    Boot.main(classOf[SparklingDemo], args)
  }

  def userMain(args: Array[String]):Unit = {
    // Now we are in H2O classloader, hurray!
    // So serve a glass of water from Spark RDD
    H2O.main(args)
    try {
      // Execute a simple demo
      prostateDemo(/*frameExtractor = DistributedFrameExtractor*/)
    } catch { // only for DEBUG - see what went wrong
      case e:Throwable => e.printStackTrace(); throw e
    } finally {
      // Always shutdown H2O worker
      H2O.CLOUD.shutdown()
    }
  }

  def prostateDemo(frameExtractor:RDDFrameExtractor = DummyFrameExtractor):Unit = {
    // Specifies how data are extracted from RDD into Frame
    val fextract  = frameExtractor

    // Dataset to parse
    val dataset   = "/Users/michal/Devel/projects/h2o/repos/NEW.h2o.github/smalldata/logreg/prostate.csv"
    // Row parser
    val rowParser = ProstateParse
    val tableName = "prostate_table"
    // query for all tumor penetration of prostate capsule, i.e., capsule=1
    val query = "SELECT * FROM prostate_table WHERE capsule=1"

    // Connect to shark cluster and make a query over prostate, transfer data into H2O
    val frame:Frame = executeSpark[Prostate](dataset, rowParser, fextract, tableName, query)

    println("Extracted frame from Spark:")
    println(if (frame!=null) frame.toStringAll else "<nothing>")

  }

  def executeSpark[S <: Product : ClassTag : TypeTag](dataset: String, rowParser: Parser[S], frameExtractor: RDDFrameExtractor, tableName:String, query:String):Frame = {
    val sc = createSparkContext()
    val data = sc.textFile(dataset,2).cache()

    // SQL query over RDD
    val sqlContext = new SQLContext(sc)
    // make visible all members of sqlContext object
    import sqlContext._
    import SchemaUtils._
    // Dummy parsing so far :-/
    val table:RDD[S] = data.map(_.split(",")).map(row => rowParser(row))
    table.registerAsTable(tableName)

    val result = sql(query)
    val f = frameExtractor[S](result)
    sc.stop()
    f // return value
  }

  private def createSparkContext(): SparkContext = {
    val conf = new SparkConf()
      .setMaster("local")
      //.setMaster("spark://localhost:7077") // Use local
      .setAppName(APP_NAME)
      //.setJars(SparkContext.jarOfClass(classOf[SparklingDemo]) ++ Seq("h2o.jar"))
      .set("spark.executor.memory", "1g")
    new SparkContext(conf)
  }

  /**
   * Dummy extractor of data from RDD.
   * <p>It fetch all data locally and fill the frame</p>
   *
   * <p>So far no handling of Enums, no compression of floats</p>
   */
  object DummyFrameExtractor extends RDDFrameExtractor {
    def apply[S <: Product : TypeTag](rdd: RDD[org.apache.spark.sql.Row]): Frame = {
      // Obtain schema from
      val cols: Seq[Attribute] = ScalaReflection.attributesFor[S]
      // Collect
      val names = cols.map(a => a.name)
      val types = cols.map(a => a.dataType)
      val ncol = names.length
      // Create keys for new frame in a new vector group
      val vectorKeys = new VectorGroup().addVecs(ncol)
      // Create a set of appendable vectors representing the frame
      val avecs = vectorKeys.map(key => new AppendableVec(key))
      // Create a set of new chunks for each vector
      val ncs = avecs.map(av => new NewChunk(av, 0))

      // Really dummy version to fill a frame
      // Right now we cannot fill NewChunks directly in foreach (since NewChunk is not Serializable), so we fetch
      // data to this local driver and put them into frame after.
      // Another version would be to force each node in the cluster to fetch its own
      // partition and fill its own part of frame
      val fs = new Futures
      val localData = rdd.collect()
      fillNewChunks(ncs, localData, types)
      // Close all guys
      ncs.foreach(_.close(0, fs))
      val vecs = avecs.map(av => av.close(fs))
      // Return a new frame
      new Frame(names.toArray, vecs)
    }
  }

  /** A frame extractor which goes around H2O cloud and force
    * each node to load data from a specified part of RDD.
    */
  object DistributedFrameExtractor extends RDDFrameExtractor {
    def apply[S <: Product : TypeTag](v: RDD[org.apache.spark.sql.Row]): Frame = {

      new PartitionExtractor().invokeOnAllNodes()
      null
    }

    /**
     */
    class PartitionExtractor extends DRemoteTask[PartitionExtractor] {
      def lcompute():Unit = {
        // Connect to Spark cloud
        val sc = createSparkContext()
        //sc.get
        // Get create RDD and query for its partition data assigned for this node

        tryComplete()
      }
      private def isMyPartition(p: Partition):Boolean = (p.index % H2O.CLOUD.size() == H2O.SELF.index())
      def reduce(drt: PartitionExtractor):Unit = {}
    }
  }

  private def fillNewChunks(ncs: Array[NewChunk], localData: Array[org.apache.spark.sql.Row], types: Seq[DataType]) = {
    localData.foreach(row => {
      for (i <- 0 until row.length) {
        if (row.isNullAt(i))
          ncs(i).addNA()
        else {
          types(i) match {
            case ByteType    => ncs(i).addNum(row.getByte  (i), 0)
            case ShortType   => ncs(i).addNum(row.getShort (i), 0)
            case IntegerType => ncs(i).addNum(row.getInt   (i), 0)
            case LongType    => ncs(i).addNum(row.getLong  (i), 0)
            case FloatType   => ncs(i).addNum(row.getFloat (i))
            case DoubleType  => ncs(i).addNum(row.getDouble(i))
            case StringType  => ncs(i).addEnum(0) // FIXME
          }
        }
      }
    })
  }
}

class SparklingDemo {
}
