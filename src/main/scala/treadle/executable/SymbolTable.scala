// See LICENSE for license details.

package treadle.executable

import firrtl._
import firrtl.graph.DiGraph
import firrtl.ir._
import treadle.{BlackBoxFactory, BlackBoxImplementation, FindModule, TreadleException}
import logger.LazyLogging

import scala.collection.immutable.Set
import scala.collection.mutable

class SymbolTable(nameToSymbol: mutable.HashMap[String, Symbol]) {

  var childrenOf: DiGraph[Symbol] = DiGraph[Symbol](Map.empty[Symbol, Set[Symbol]])
  var parentsOf:  DiGraph[Symbol] = DiGraph[Symbol](Map.empty[Symbol, Set[Symbol]])

  var orphans: Seq[Symbol] = Seq.empty

  private val toAssigner: mutable.HashMap[Symbol, Assigner] = new mutable.HashMap()
  def addAssigner(symbol: Symbol, assigner: Assigner): Unit = {
    if(toAssigner.contains(symbol)) {
      throw new TreadleException(s"Assigner already exists for $symbol")
    }
    toAssigner(symbol) = assigner
  }
  def hasAssigner(symbol: Symbol): Boolean = {
    toAssigner.contains(symbol)
  }

  private val toBlackBoxImplementation: mutable.HashMap[Symbol, BlackBoxImplementation] = new mutable.HashMap()
  def addBlackBoxImplementation(symbol: Symbol, blackBoxImplementation: BlackBoxImplementation): Unit = {
    if(toBlackBoxImplementation.contains(symbol)) {
      throw new TreadleException(s"Assigner already exists for $symbol")
    }
    toBlackBoxImplementation(symbol) = blackBoxImplementation
  }

  def allocateData(dataStore: DataStore): Unit = {
    nameToSymbol.values.foreach { symbol =>
      symbol.index = dataStore.getIndex(symbol.dataSize, symbol.slots)
    }
    dataStore.allocateBuffers()
  }

  def size: Int = nameToSymbol.size
  def keys:Iterable[String] = nameToSymbol.keys
  def symbols:Iterable[Symbol] = nameToSymbol.values

  val instanceNames:    mutable.HashSet[String] = new mutable.HashSet[String]
  val registerNames:    mutable.HashSet[String] = new mutable.HashSet[String]
  val inputPortsNames:  mutable.HashSet[String] = new mutable.HashSet[String]
  val outputPortsNames: mutable.HashSet[String] = new mutable.HashSet[String]

  val clockSignals   : mutable.HashMap[Symbol, Symbol] = new mutable.HashMap[Symbol, Symbol]
  val triggersFor    : mutable.HashMap[Symbol, Symbol] = new mutable.HashMap[Symbol, Symbol]
  val stopToStopInfo : mutable.HashMap[Stop, StopInfo] = new mutable.HashMap[Stop, StopInfo]

  def isRegister(name: String): Boolean = registerNames.contains(name)
  def isTopLevelInput(name: String): Boolean = inputPortsNames.contains(name)

  def apply(name: String): Symbol = nameToSymbol(name)

  def getSymbolFromGetter(expressionResult: ExpressionResult, dataStore: DataStore): Option[Symbol] = {
    expressionResult match {
      case dataStore.GetInt(index)  => symbols.find { symbol => symbol.dataSize == IntSize && symbol.index == index}
      case dataStore.GetLong(index) => symbols.find { symbol => symbol.dataSize == LongSize && symbol.index == index}
      case dataStore.GetBig(index)  => symbols.find { symbol => symbol.dataSize == BigSize && symbol.index == index}
      case _ => None
    }
  }

  /**
    * Find all the sources of symbol that are not non-clock inputs.
    * Sinks are used here because we are working with the parents of graph
    * This was needed because clocks of memory or other submodules may have
    * a non-trivial connection to parent clocks
    * @param symbol sinks needed for this
    * @return
    */
  def getSourcesOf(symbol: Symbol): Set[Symbol] = {
    val parents = parentsOf.reachableFrom(symbol)
    val sinks   = parentsOf.findSinks
    val nonInputSinks = sinks.filterNot { sink =>
      inputPortsNames.contains(sink.name) && sink.firrtlType != ClockType }
    val possible = parents.intersect(nonInputSinks)

    possible.toSet
  }

  def getParents(symbols: Seq[Symbol]): Set[Symbol] = {
    symbols.flatMap { symbol =>
      parentsOf.reachableFrom(symbol)
    }.toSet
  }

  def getChildren(symbols: Seq[Symbol]): Set[Symbol] = {
    symbols.flatMap { symbol =>
      childrenOf.reachableFrom(symbol)
    }.toSet
  }

  def allAssigners(): Seq[Assigner] = {
    toAssigner.values.toSeq
  }

  def inputChildrenAssigners(): Seq[Assigner] = {
    val assigners = getChildren(inputPortsNames.map(nameToSymbol(_)).toSeq)
      .flatMap { symbol => toAssigner.get(symbol)}
      .toSeq
    assigners
  }

  def getAssigners(symbols: Seq[Symbol]): Seq[Assigner] = {
    val assigners = symbols.flatMap { symbol => toAssigner.get(symbol) }
    assigners
  }

  def getBlackboxImplementation(symbol: Symbol): Option[BlackBoxImplementation] = {
    toBlackBoxImplementation.get(symbol)
  }

  def get(name: String): Option[Symbol] = nameToSymbol.get(name)
  def getOrElse(name: String, default: => Symbol): Symbol = nameToSymbol.getOrElse(name, default)

  def contains(name: String): Boolean = nameToSymbol.contains(name)

  def render: String = {
    Symbol.renderHeader + "\n" +
    keys.toArray.sorted.map { name =>
      nameToSymbol(name).render
    }.mkString("\n")
  }
}

object SymbolTable extends LazyLogging {

  val UpTransitionSuffix = "/rising"
  def makeUpTransitionName(name: String): String = name + UpTransitionSuffix
  def makeUpTransitionName(symbol: Symbol): String = symbol.name + UpTransitionSuffix

  val RegisterInputSuffix = "/in"
  def makeRegisterInputName(name: String): String = name + RegisterInputSuffix
  def makeRegisterInputName(symbol: Symbol): String = symbol.name + RegisterInputSuffix

  var stopSymbolsFound: Int = 0
  def makeStopName(): String = {
    stopSymbolsFound += 1
    s"/stop${stopSymbolsFound - 1}"
  }

  def apply(nameToSymbol: mutable.HashMap[String, Symbol]): SymbolTable = new SymbolTable(nameToSymbol)

  //scalastyle:off cyclomatic.complexity method.length
  def apply(
      circuit: Circuit,
      blackBoxFactories: Seq[BlackBoxFactory] = Seq.empty,
      allowCycles: Boolean = false
  ): SymbolTable = {

    type SymbolSet = Set[Symbol]

    val nameToSymbol = new mutable.HashMap[String, Symbol]()
    def addSymbol(symbol: Symbol): Unit = {
      if(nameToSymbol.contains(symbol.name)) {
        throw new TreadleException(s"Symbol table attempting to re-add symbol $symbol")
      }
      else {
        nameToSymbol(symbol.name) = symbol
      }
    }

    val sensitivityGraphBuilder: SensitivityGraphBuilder = new SensitivityGraphBuilder

    val instanceNames = new mutable.HashSet[String]
    val registerNames = new mutable.HashSet[String]
    val inputPorts    = new mutable.HashSet[String]
    val outputPorts   = new mutable.HashSet[String]

    val clockSignals    = new mutable.HashMap[Symbol, Symbol]
    val triggersFor     = new mutable.HashMap[Symbol, Symbol]
    val stopToStopInfo  = new mutable.HashMap[Stop, StopInfo]

    val blackBoxImplementations = new mutable.HashMap[Symbol, BlackBoxImplementation]()

    def addDependency(sensitiveSymbol: Symbol, drivingSymbols: Set[Symbol]): Unit = {
      drivingSymbols.foreach { drivingSymbol =>
        sensitivityGraphBuilder.addSensitivity(drivingSymbol = drivingSymbol, sensitiveSymbol)
      }
    }

    // scalastyle:off
    def processDependencyStatements(modulePrefix: String, s: Statement): Unit = {
      def expand(name: String): String = if (modulePrefix.isEmpty) name else modulePrefix + "." + name

      def expressionToReferences(expression: Expression): SymbolSet = {
        val result = expression match {
          case Mux(condition, trueExpression, falseExpression, _) =>
            expressionToReferences(condition) ++
              expressionToReferences(trueExpression) ++
              expressionToReferences(falseExpression)

          case _: WRef | _: WSubField | _: WSubIndex =>
            Set(nameToSymbol(expand(expression.serialize)))

          case ValidIf(condition, value, _) =>
            expressionToReferences(condition) ++ expressionToReferences(value)
          case DoPrim(_, args, _, _) =>
            args.foldLeft(Set.empty[Symbol]) { case (accum, expr) => accum ++ expressionToReferences(expr) }
          case _: UIntLiteral | _: SIntLiteral =>
            Set.empty[Symbol]
          case _ =>
            throw new Exception(s"expressionToReferences:error: unhandled expression $expression")
        }
        result
      }

      def getClockSymbol(expression: Expression): Option[Symbol] = {
        val references = expressionToReferences(expression)
        val clocks = references.filter { symbol =>
          symbol.firrtlType == firrtl.ir.ClockType
        }
        clocks.headOption
      }

      def getClockRisingSymbol(clockSymbol: Symbol, info: Info = NoInfo): Symbol = {
        val risingSymbolName = SymbolTable.makeUpTransitionName(clockSymbol)
        nameToSymbol.getOrElseUpdate(
          risingSymbolName,
          Symbol(risingSymbolName, firrtl.ir.ClockType, WireKind, info = info)
        )
      }

      s match {
        case block: Block =>
          block.stmts.foreach { subStatement =>
            processDependencyStatements(modulePrefix, subStatement)
          }

        case con: Connect =>
          con.loc match {
            case (_: WRef | _: WSubField | _: WSubIndex) =>
              val name = if (registerNames.contains(expand(con.loc.serialize))) {
                SymbolTable.makeRegisterInputName(expand(con.loc.serialize))
              }
              else {
                expand(con.loc.serialize)
              }
              val symbol = nameToSymbol(name)

              addDependency(symbol, expressionToReferences(con.expr))
          }

        case WDefInstance(info, instanceName, moduleName, _) =>
          /*
          Port symbols are created by ProcessPorts
           */
          val expandedName = expand(instanceName)
          instanceNames += expandedName
          val instanceSymbol = Symbol(expandedName, IntSize, UnsignedInt, WireKind, 1, 1, UIntType(IntWidth(1)), info)
          addSymbol(instanceSymbol)

          val subModule = FindModule(moduleName, circuit)
          val newPrefix = if (modulePrefix.isEmpty) instanceName else modulePrefix + "." + instanceName
          logger.debug(s"declaration:WDefInstance:$instanceName:$moduleName prefix now $newPrefix")
          processModule(newPrefix, subModule)

          subModule match {
            case extModule: ExtModule =>
              blackBoxImplementations.get(instanceSymbol) match {
                case Some(implementation) =>

                  for (port <- extModule.ports) {
                    if(port.direction == Output) {
                      val portSymbol = nameToSymbol(expand(instanceName + "." + port.name))
                      implementation.outputDependencies(port.name).foreach { inputName =>
                        val inputSymbol = nameToSymbol(expand(instanceName + "." + inputName))
                        addDependency(portSymbol, Set(inputSymbol))
                      }
                    }
                  }
                case _ =>
                  println(
                    s"""WARNING: external module "${extModule.defname}"($modulePrefix:${extModule.name})""" +
                      """was not matched with an implementation""")
              }
            case _ =>
            // not external module, it was processed above
          }

        case DefNode(info, name, expression) =>
          logger.debug(s"declaration:DefNode:$name:${expression.serialize} ${expressionToReferences(expression)}")
          val expandedName = expand(name)
          val symbol = Symbol(expandedName, expression.tpe, firrtl.NodeKind, info = info)
          addSymbol(symbol)
          addDependency(symbol, expressionToReferences(expression))

        case DefWire(info, name, tpe) =>
          logger.debug(s"declaration:DefWire:$name")
          val expandedName = expand(name)
          val symbol = Symbol(expandedName, tpe, WireKind, info = info)
          addSymbol(symbol)

        case DefRegister(info, name, tpe, clockExpression, resetExpression, _) =>
          val expandedName = expand(name)

          val registerIn = Symbol(SymbolTable.makeRegisterInputName(expandedName), tpe, RegKind, info = info)
          val registerOut = Symbol(expandedName, tpe, RegKind, info = info)
          registerNames += registerOut.name
          addSymbol(registerIn)
          addSymbol(registerOut)

//          expressionToReferences(clockExpression).headOption.foreach { clockSymbol =>
          getClockSymbol(clockExpression).foreach { clockSymbol =>
            val registerClockPrevious = getClockRisingSymbol(clockSymbol)
            addDependency(registerClockPrevious, Set(clockSymbol, registerOut))

            clockSignals(clockSymbol) = registerClockPrevious
            triggersFor(registerOut)  = registerClockPrevious
          }

          addDependency(registerOut, expressionToReferences(clockExpression))
          addDependency(registerIn, expressionToReferences(resetExpression))

        case defMemory: DefMemory =>
          val expandedName = expand(defMemory.name)
          logger.debug(s"declaration:DefMemory:${defMemory.name} becomes $expandedName")

          Memory.buildSymbols(defMemory, expandedName, sensitivityGraphBuilder).foreach { symbol =>
            addSymbol(symbol)
          }

        case stop @ Stop(info, _, clockExpression, _)   =>
          getClockSymbol(clockExpression) match {
            case Some(clockSymbol) =>
              val risingSymbol = getClockRisingSymbol(clockSymbol)

              val stopSymbolName = makeStopName()
              val stopSymbol = Symbol(stopSymbolName, IntSize, UnsignedInt, WireKind, 1, 1, UIntType(IntWidth(1)), info)
              addSymbol(stopSymbol)
              stopToStopInfo(stop) = StopInfo(stopSymbol, risingSymbol)
              addDependency(stopSymbol, Set(clockSymbol))
              if(! nameToSymbol.contains(StopOp.StopOpSymbol.name)) {
                addSymbol(StopOp.StopOpSymbol)
              }

              clockSignals(clockSymbol) = risingSymbol
            case _ =>
              throw new TreadleException(s"Can't find clock for $stop")
          }

        case _: Print  =>

        case EmptyStmt =>

        case invalid: IsInvalid =>
          logger.debug(f"IsInvalid found for ${invalid.expr}%20s")

        case conditionally: Conditionally =>
          throw new TreadleException(s"conditionally unsupported in engine $conditionally")
        case _ =>
          println(s"TODO: Unhandled statement $s")
      }
    }

    // scalastyle:on

    def processExternalInstance(extModule: ExtModule,
                                modulePrefix: String,
                                instance: BlackBoxImplementation): Unit = {
      def expand(name: String): String = modulePrefix + "." + name

      val instanceSymbol = nameToSymbol(modulePrefix)
      blackBoxImplementations(instanceSymbol) = instance

      for (outputPort <- extModule.ports if outputPort.direction == Output) {
        instance.outputDependencies(outputPort.name).foreach { inputPortName =>
          sensitivityGraphBuilder.addSensitivity(
            drivingSymbol = nameToSymbol(expand(inputPortName)),
            sensitiveSymbol = nameToSymbol(expand(outputPort.name))
          )
        }
      }
    }

    def processModule(modulePrefix: String, myModule: DefModule): Unit = {
      def expand(name: String): String = if (modulePrefix.nonEmpty) modulePrefix + "." + name else name

      def processPorts(module: DefModule): Unit = {
        for (port <- module.ports) {
          val expandedName = expand(port.name)
          val symbol = Symbol(expandedName, port.tpe, PortKind)
          addSymbol(symbol)
          if(port.tpe == firrtl.ir.ClockType) {
            val upTransitionName = SymbolTable. makeUpTransitionName(expandedName)
            val upTransitionSymbol = Symbol(upTransitionName, port.tpe, PortKind)
            addSymbol(upTransitionSymbol)
            addDependency(upTransitionSymbol, Set(symbol))
            clockSignals(symbol) = upTransitionSymbol
          }
          if(modulePrefix.isEmpty) {  // this is true only at top level
            if(port.direction == Input) {
              inputPorts += symbol.name
            }
            else if(port.direction == Output) {
              outputPorts += symbol.name
            }
          }
        }
      }

      myModule match {
        case module: Module =>
          processPorts(module)
          processDependencyStatements(modulePrefix, module.body)
        case extModule: ExtModule => // Look to see if we have an implementation for this
          logger.debug(s"got external module ${extModule.name} instance $modulePrefix")
          processPorts(extModule)
          /* use exists while looking for the right factory, short circuits iteration when found */
          logger.debug(s"Factories: ${blackBoxFactories.mkString("\n")}")
          val implementationFound = blackBoxFactories.exists { factory =>
            logger.debug("Found an existing factory")
            factory.createInstance(modulePrefix, extModule.defname) match {
              case Some(implementation) =>
                processExternalInstance(extModule, modulePrefix, implementation)
                true
              case _ => false
            }
          }
          if (!implementationFound) {
            println(
              s"""WARNING: external module "${extModule.defname}"($modulePrefix:${extModule.name})""" +
                """was not matched with an implementation""")
          }
      }
    }

    val module = FindModule(circuit.main, circuit) match {
      case regularModule: firrtl.ir.Module => regularModule
      case externalModule: firrtl.ir.ExtModule =>
        throw TreadleException(s"Top level module must be a regular module $externalModule")
      case x =>
        throw TreadleException(s"Top level module is not the right kind of module $x")
    }

    logger.trace(s"Build SymbolTable pass 1 -- gather starting")
    processModule("", module)
    logger.trace(s"Build SymbolTable pass 1 -- gather complete: ${nameToSymbol.size} entries found")

    // scalastyle:on cyclomatic.complexity

    val symbolTable = SymbolTable(nameToSymbol)
    symbolTable.instanceNames            ++= instanceNames
    symbolTable.registerNames            ++= registerNames
    symbolTable.inputPortsNames          ++= inputPorts
    symbolTable.outputPortsNames         ++= outputPorts
    symbolTable.toBlackBoxImplementation ++= blackBoxImplementations
    symbolTable.clockSignals             ++= clockSignals
    symbolTable.stopToStopInfo           ++= stopToStopInfo
    symbolTable.triggersFor              ++= triggersFor

    symbolTable.parentsOf                = sensitivityGraphBuilder.getParentsOfDiGraph
    symbolTable.childrenOf               = sensitivityGraphBuilder.getChildrenOfDiGraph

    val sorted: Seq[Symbol] = try {
      symbolTable.childrenOf.linearize
    }
    catch {
      case e: firrtl.graph.CyclicException =>
        if(allowCycles) {
          symbolTable.symbols.toSeq
        }
        else {
          throw e
        }
    }
    logger.trace(s"Build SymbolTable pass 2 -- linearize complete")


    sorted.zipWithIndex.foreach { case (symbol, index) => symbol.cardinalNumber = index }

    logger.trace(s"Build SymbolTable pass 3 -- sort complete")
    // logger.debug(s"Sorted elements\n${sorted.map(_.name).mkString("\n")}")

    symbolTable.orphans = sensitivityGraphBuilder.orphans(symbolTable)
    logger.trace(
      s"Build Symbol table pass 4 -- find sources. ${symbolTable.orphans.length} non-input non-register sinks found")

    logger.info(s"SymbolTable is built")

    symbolTable
  }
}