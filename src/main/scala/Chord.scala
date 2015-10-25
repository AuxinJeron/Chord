/**
 * Created by leon on 10/23/15.
 */

import akka.actor.{ActorRef, Actor, Props, ActorSystem}
import akka.event.Logging
import akka.pattern.Patterns
import scala.concurrent.{ExecutionContext, Await, Future}
import ExecutionContext.Implicits.global
import scala.util.Random
import akka.util.Timeout
import scala.util.{Failure, Success}
import akka.pattern.Patterns._
import scala.concurrent.duration.Duration
import java.security.MessageDigest

case object NodeAskSuccessor
case object NodeAskPredecessor
case object NodeAskIdentifier
case object NodeAskJoined
case class ManagerStartMessage(numNodes: Int, numRequests: Int)
case class ManagerAddJoinedNode(ipAddress: String)
case class ManagerJoinNode(node: ActorRef, index: Int)
case class SendMessage(message: String, times: Int)
case class FindSuccessor(id: BigInt)
case class FindPredecessor(id: BigInt)
case class ChangePredecessor(ipAddress: String)
case class ChangeSuccessor(ipAddress: String)
case class UpdateFingerTables(ipAddress: String, index: Int)
case class ClosestPreFinger(id: BigInt)
case class Successor()
case class NodeJoin(ipAddress: String)
case class NodeEnd()

object Chord {
  def main (args: Array[String]): Unit = {
//    println("Hash Value is " + hashManager.getHashInt("leon.li@ufl.edu"))
    if (args.length != 2) {
      println("Invalid arguments, please input arguments as the format <numNodes> <numRequests>")
      return
    }
    val system = ActorSystem("ChordSystem")
    val manager = system.actorOf(Props(new Manager()), "Manager")
    manager ! ManagerStartMessage(args(0).toInt, args(1).toInt)
  }
}

object hashManager {
  // consistentM can be modified to change the circle size
  val consistentM: Int = 12
  val slotsSize: BigInt = BigInt(2).pow(consistentM)
  def getHashInt(value: String): BigInt = {
    val msgDigest = MessageDigest.getInstance("SHA-1")
    msgDigest.update(value.getBytes("ascii"))
    BigInt.apply(msgDigest.digest())
  }
  def getHashString(value: String): String = {
    val msgDigest = MessageDigest.getInstance("SHA-1")
    msgDigest.update(value.getBytes("ascii"))
    msgDigest.digest().
      foldLeft("")((s: String, b: Byte) => s +
      Character.forDigit((b & 0xf0) >> 4, 16) +
      Character.forDigit(b & 0x0f, 16))
  }
}

object messageGenerater {
  def randomMessage(length: Int): String = {
    val message:Array[Byte] = new Array[Byte](length)
    for (i <- message.indices) {
      var v = 0
      do {
        v = Random.nextInt() % 127
      } while (v < 33)
      message(i) = v.toByte
    }
    return new String(message, "ascii")
  }
}

class Manager extends Actor {
  var nodes = 0
  var endNodes = 0
  var timeout: Timeout = new Timeout(Duration.create(5000, "milliseconds"))

  def getNodeRefByAddress(ipAddress: String): ActorRef = {
    val future = context.system.actorSelection("/user/Manager/" + ipAddress).resolveOne()(timeout)
    return Await.result(future, timeout.duration)
  }

  def getNodeJoined(node: ActorRef): Boolean = {
    val future: Future[Object] = Patterns.ask(node, NodeAskJoined, timeout)
    return Await.result(future, timeout.duration).asInstanceOf[Boolean]
  }

  def joinNode(node: ActorRef, index: Int) = {
    try {
      var ipAddress = String.format("node" + Random.nextInt() % index)
      var nodeRef = this.getNodeRefByAddress(ipAddress)
      while (this.getNodeJoined(nodeRef) == false) {
        Thread.sleep(1000)
        ipAddress = String.format("node" + Random.nextInt() % index)
        nodeRef = this.getNodeRefByAddress(ipAddress)
      }
      node ! NodeJoin(ipAddress)
    }
    catch {
      case e: Exception =>
        Thread.sleep(1000)
        self ! ManagerJoinNode(node, index)
    }
  }

  def receive = {
    case ManagerStartMessage(nodesNum, requestsNum) =>
      println("Manager begin to join " + nodesNum + " nodes, each with requests num " + requestsNum)
      this.nodes = nodesNum
      for (i <- 0 until nodesNum) {
        val node = context.actorOf(Props(new Node("node" + i, requestsNum)), "node" + i)
        if (i == 0) {
          node ! NodeJoin("")
        }
        else {
          this.joinNode(node, i)
        }
      }
    case ManagerJoinNode(node: ActorRef, index: Int) =>
      this.joinNode(node, index)
    case NodeEnd() =>
      endNodes += 1
      if (endNodes == nodes)
        context.system.shutdown()
  }
}

class Node(ipAddress: String, requestsNum: Int) extends Actor {
  var identifier: BigInt = hashManager.getHashInt(ipAddress) % (BigInt(2).pow(hashManager.consistentM))
  var sentMessages: Int = 0
  var fingerTable: Array[Finger] = Array.fill[Finger](hashManager.consistentM + 1)(new Finger())
  var successor: String = ""
  var predecessor: String = ""
  var timeout: Timeout = new Timeout(Duration.create(Random.nextInt() % 10000 + 3000, "milliseconds"))
  val log = Logging(context.system, this)
  var joined = false

  // get remote information
  def getNodeRefByAddress(ipAddress: String): ActorRef = {
    if (ipAddress == "" || ipAddress == null) {
        throw new Exception("Empty ipAddress")
    }
    var actorRef: ActorRef = self
    if (ipAddress == this.ipAddress)
      return self
    val future = context.system.actorSelection("/user/Manager/" + ipAddress).resolveOne()(timeout)
    future.onComplete {
      case Success(value) =>
        //println(s"Got the callback, meaning = $value")
      case Failure(e) =>
        //println(s"Exception: $e")
        throw e
    }
    try {
      actorRef = Await.result(future, timeout.duration)
      return actorRef
    }
    catch {
      case e: Exception =>
        //println(s"Exception: $e")
        throw e
    }
  }

  def getNodeIdentifierByAddress(ipAddress: String): BigInt = {
    return hashManager.getHashInt(ipAddress) % (BigInt(2).pow(hashManager.consistentM))
  }

  def getNodeIdentifier(node: ActorRef): BigInt = {
    if (node == self) {
      return this.identifier
    }
    val future: Future[Object] = Patterns.ask(node, NodeAskIdentifier, timeout)
    try {
      val result: BigInt = Await.result(future, timeout.duration).asInstanceOf[BigInt]
      return result
    }
    catch {
      case e: Exception =>
        //println(s"Exception: $e")
        throw e
    }
  }

  def getNodePredecessor(node: ActorRef): String = {
    if (node == self) {
      return this.predecessor
    }
    val future: Future[Object] = Patterns.ask(node, NodeAskPredecessor, timeout)
    try {
      val result: String = Await.result(future, timeout.duration).asInstanceOf[String]
      return result
    }
    catch {
      case e: Exception =>
        //println(s"Exception: $e")
        throw e
    }
  }

  def getNodeSuccessor(node: ActorRef): String = {
    if (node == self) {
      return this.successor
    }
    val future: Future[Object] = Patterns.ask(node, NodeAskSuccessor, timeout)
    try {
      val result: String = Await.result(future, timeout.duration).asInstanceOf[String]
      return result
    }
    catch {
      case e: Exception =>
        //println(s"Exception: $e")
        throw e
    }
  }

  def getNodeJoined(node: ActorRef): Boolean = {
    if (node == self) {
      return this.joined
    }
    val future: Future[Object] = Patterns.ask(node, NodeAskJoined, timeout)
    try {
      val result: Boolean = Await.result(future, timeout.duration).asInstanceOf[Boolean]
      return result
    }
    catch {
      case e: Exception =>
        throw e
    }
  }

  def findSuccessorFrom(node: ActorRef, id: BigInt): String = {
    if (node == self) {
      return this.findSuccessor(id)
    }
    val future: Future[Object] = Patterns.ask(node, FindSuccessor(id), timeout)
    try {
      val result: String = Await.result(future, timeout.duration).asInstanceOf[String]
      return result
    }
    catch {
      case e: Exception =>
        //println(s"Exception: $e")
        throw e
    }
  }

  def getClosestPreFinger(node: ActorRef, id: BigInt): String = {
    if (node == self) {
      return this.closestPrecedingFinger(id)
    }
    val future: Future[Object] = Patterns.ask(node, ClosestPreFinger(id), timeout)
    try {
      val result: String = Await.result(future, timeout.duration).asInstanceOf[String]
      return result
    }
    catch {
      case e: Exception =>
        //println(s"Exception: $e")
        throw e
    }
  }

  // algorithm
  def findSuccessor(id: BigInt): String = {
    log.info(this.ipAddress + " begin to find successor for " + id)
    val preNode = this.findPredecessor(id)
    // the actorRef need to be checked
    val result = this.getNodeSuccessor(this.getNodeRefByAddress(preNode))
    // TODO: check the failure
    return result
  }

  def findPredecessor(id: BigInt): String = {
    log.info(this.ipAddress + " begin to find predecessor for " + id)
    var preNode = this.ipAddress
    var preNodeRef = this.getNodeRefByAddress(preNode)
    var preNodeSuccessor = this.getNodeSuccessor(preNodeRef)
    var preNodeSuccessorRef = this.getNodeRefByAddress(preNodeSuccessor)

    var preNodeId = this.getNodeIdentifier(preNodeRef)
    var preNodeSuccessorId = this.getNodeIdentifier(preNodeSuccessorRef)
    while (this.checkIdentifierInRange(id, preNodeId, preNodeSuccessorId)) {
      preNode = this.getClosestPreFinger(preNodeRef, id)
      preNodeRef = this.getNodeRefByAddress(preNode)
      preNodeSuccessor = this.getNodeSuccessor(preNodeRef)
      preNodeSuccessorRef = this.getNodeRefByAddress(preNodeSuccessor)

      preNodeId = this.getNodeIdentifier(preNodeRef)
      preNodeSuccessorId = this.getNodeIdentifier(preNodeSuccessorRef)
    }

    return preNode
  }

  def checkIdentifierInRange(id: BigInt, start: BigInt, end: BigInt): Boolean = {
    if (end > start) {
      if (id <= start || id > end) return true
      else return false
    } else {
      if (id <= start && id > end) return true
      else return false
    }
  }

  def closestPrecedingFinger(id: BigInt): String = {
    for (i <- 10 to (1, -1)) {
      val tempId = this.getNodeIdentifierByAddress(this.fingerTable(i).nodeIpAddress)
      if (id >= this.identifier) {
        if (tempId > this.identifier && tempId < id)
          return this.fingerTable(i).nodeIpAddress
      }
      else {
        if (tempId > this.identifier || tempId < id)
          return this.fingerTable(i).nodeIpAddress
      }
    }
    return this.ipAddress
  }

  def joinBy(helpNode: String) = {
    log.info("Begin to join " + this.ipAddress + " to network by " + helpNode)
    if (helpNode != "") {
      try {
        this.initFingerTable(helpNode)
        this.updateOthers() // move keys in (predecessor, n] from successor
        log.info(this.ipAddress + " has been joined")
        this.joined = true
        context.actorSelection("/user/Manager") ! ManagerAddJoinedNode(this.ipAddress)
        this.sendMessage()
      }
      catch {
        case e: Exception =>
          val address = this.ipAddress
          log.info(s"Joining $address meets Exception: $e")
          synchronized {
            Thread.sleep(1000)
            timeout = new Timeout(Duration.create(Random.nextInt() % 10000 + 3000, "milliseconds"))
            self ! NodeJoin(helpNode)
          }
      }
    }
    else {
      for (i <- 1 to hashManager.consistentM) {
        this.fingerTable(i).nodeIpAddress = this.ipAddress
      }
      this.predecessor = this.ipAddress
      this.successor = this.ipAddress
      log.info(this.ipAddress + " has been joined")
      this.joined = true
      context.actorSelection("/user/Manager") ! ManagerAddJoinedNode(this.ipAddress)
      this.sendMessage()
    }
  }

  def initFingerTable(helpNode: String) = {
    // init fingertable start
    log.info(this.ipAddress + " init finger table with the help of " + helpNode)
    for (i <- 1 to hashManager.consistentM) {
      this.fingerTable(i).start = (this.identifier + BigInt(2).pow(i - 1)) % (BigInt(2).pow(hashManager.consistentM))
    }
    val helpNodeRef = this.getNodeRefByAddress(helpNode)
    // may it is wrong to get precedessor like this
    fingerTable(1).nodeIpAddress = this.findSuccessorFrom(helpNodeRef, fingerTable(1).start)
    log.info(this.ipAddress + " fingertable(1).nodeIpAddress = " + fingerTable(1).nodeIpAddress)
    // TODO: check whether it is true
    this.successor = fingerTable(1).nodeIpAddress
    this.predecessor = this.getNodePredecessor(this.getNodeRefByAddress(this.successor))
    this.getNodeRefByAddress(this.predecessor) ! ChangeSuccessor(this.ipAddress)
    this.getNodeRefByAddress(this.successor) ! ChangePredecessor(this.ipAddress)

    for (i <- 1 to hashManager.consistentM - 1) {
      var inRange: Boolean = false
      val fingerNodeId = this.getNodeIdentifierByAddress(this.fingerTable(i).nodeIpAddress)
      val start = this.fingerTable(i).start
      if (this.identifier < fingerNodeId &&  start >= this.identifier && start < fingerNodeId) inRange = true
      if (this.identifier >= fingerNodeId && (start >= this.identifier || start < fingerNodeId)) inRange = true
      if (inRange == true) {
        this.fingerTable(i + 1).nodeIpAddress = fingerTable(i).nodeIpAddress
      }
      else {
        this.fingerTable(i + 1).nodeIpAddress = this.findSuccessorFrom(helpNodeRef, this.fingerTable(i + 1).start)
      }
    }

    for (i <- 1 until hashManager.consistentM) {
      this.fingerTable(i).interval = new ChordRange(this.fingerTable(i).start, this.fingerTable(i + 1).start)
    }
    this.fingerTable(hashManager.consistentM).interval = new ChordRange(this.fingerTable(hashManager.consistentM).start,
      this.fingerTable(1).start - 1)
  }

  // update all nodes whose finger tables should refer to n
  def updateOthers() = {
    var preNode: String = ""
    for (i <- 1 to hashManager.consistentM) {
      preNode = this.findPredecessor(this.identifier - BigInt(2).pow(i - 1))
    }
  }

  def updateFingerTables(ipAddress: String, index: Int) = {
    val nodeId = this.getNodeIdentifierByAddress(ipAddress)
    val fingerNodeId = this.getNodeIdentifierByAddress(this.fingerTable(index).nodeIpAddress)

    var inRange = false
    if (this.identifier < fingerNodeId && nodeId >= this.identifier && nodeId < fingerNodeId) inRange = true
    if (this.identifier >= fingerNodeId && (nodeId >= this.identifier || nodeId < fingerNodeId)) inRange = true

    if (inRange == true) {
      this.fingerTable(index).nodeIpAddress = ipAddress
      val preNodeRef = this.getNodeRefByAddress(this.predecessor)
      preNodeRef ! UpdateFingerTables(ipAddress, index)
    }
  }

  def sendMessage() = {
    for (i <- 0 until this.requestsNum) {
      val message = messageGenerater.randomMessage(20)
      //this.findSuccessor(hashManager.getHashInt(message) % BigInt(2).pow(hashManager.consistentM))
      this.sendMessageRound(message, 0)
    }
  }

  def sendMessageRound(message: String, times: Int) = {
    if (times >= 100) {
      this.sentMessages += 1
    }
    else {
      try {
        val messageId = hashManager.getHashInt(message) % BigInt(2).pow(hashManager.consistentM)
        val successor = this.findSuccessor(messageId)
        log.info(this.ipAddress + " sent message["+ message +"] with identifier [" + messageId + "] found the successor " + successor)
        this.sentMessages += 1
      }
      catch {
        case e: Exception =>
          log.info(s"Sending message meets Exception: $e")
          synchronized {
            Thread.sleep(1000)
            timeout = new Timeout(Duration.create(Random.nextInt() % 10000 + 3000, "milliseconds"))
            self ! SendMessage(message, times + 1)
          }
      }
    }
    if (sentMessages == this.requestsNum) {
      this.end()
    }
  }

  def end() = {
    log.info(this.ipAddress + " ends")
    context.actorSelection("../../Manager") ! NodeEnd()
  }

  def receive = {
    case NodeAskSuccessor => sender() ! this.successor
    case NodeAskPredecessor => sender() ! this.predecessor
    case NodeAskIdentifier => sender() ! this.identifier
    case NodeAskJoined => sender() ! this.joined
    case ClosestPreFinger(id: BigInt) =>
      try {
        sender() ! this.closestPrecedingFinger(id)
      }
      catch {
        case e: Exception => sender() ! e
      }
    case FindSuccessor(id: BigInt) =>
      try {
        sender() ! this.findSuccessor(id)
      }
      catch {
        case e: Exception => sender() ! e
      }
    case FindPredecessor(id: BigInt) =>
      try {
        sender() ! this.findPredecessor(id)
      }
      catch {
        case e: Exception => sender() ! e
      }
    case ChangePredecessor(ipAddress: String) => this.predecessor = ipAddress
    case ChangeSuccessor(ipAddress: String) => this.successor = ipAddress
    case UpdateFingerTables(ipAddress: String, index: Int) => this.updateFingerTables(ipAddress, index)
    case NodeJoin(ipAddress: String) => this.joinBy(ipAddress)
    case SendMessage(message: String, times: Int) => this.sendMessageRound(message, times)
  }
}

class  Finger {
  var start: BigInt = 0
  var interval: ChordRange = new ChordRange(0, 0)
  var nodeIpAddress: String = ""
}

class ChordRange(start: BigInt, end: BigInt) {

}

