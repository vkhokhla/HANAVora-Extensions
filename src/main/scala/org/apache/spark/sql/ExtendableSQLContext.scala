package org.apache.spark.sql

import org.apache.spark.SparkContext
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.sql.catalyst.AbstractSparkSQLParser
import org.apache.spark.sql.catalyst.analysis.{Analyzer, SimpleFunctionRegistry, FunctionRegistry}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.{ExtractPythonUdfs, SparkPlan}

/**
 * Extendable SQLContext. This SQLContext takes a sequence of SQLContextExtension
 * that can provide extended parsers, resolution rules, function registries or
 * strategies.
 *
 * @param sparkContext The SparkContext.
 * @param extensions A list of extensions.
 */
@DeveloperApi
class ExtendableSQLContext(@transient override val sparkContext: SparkContext,
                           extensions : Seq[SQLContextExtension] = Nil)
  extends SQLContext(sparkContext) {

  @transient
  override protected[sql] val sqlParser : SparkSQLParser = {
    val extendedParsers = extensions.flatMap(_.sqlParser)
    if (extendedParsers.size > 1) {
      log.warn("Got more than one extended parser, but only one will have effect: {}",
        extendedParsers.head)
    }
    extendedParsers.headOption.getOrElse {
      val fallback = new catalyst.SqlParser
      new SparkSQLParser(fallback(_))
    }
  }

  @transient
  override protected[sql] lazy val functionRegistry: FunctionRegistry = {
    val extendedFunctionRegistries = extensions.flatMap(_.functionRegistry)
    if (extendedFunctionRegistries.size > 1) {
      log.warn("Got more than one extended function registry, but only one will have effect: {}",
        extendedFunctionRegistries.head)
    }
    val registry = extendedFunctionRegistries.headOption.getOrElse(new SimpleFunctionRegistry(true))
    extensions.foreach(_.registerFunctions(registry))
    registry
  }

  @transient
  override protected[sql] lazy val analyzer: Analyzer = {
    val parentRules =
      ExtractPythonUdfs ::
        sources.PreInsertCastAndRename ::
        Nil
    new Analyzer(catalog, functionRegistry, caseSensitive = true) {
      val extendedRules = extensions.flatMap(_.resolutionRules(this))
      override val extendedResolutionRules = extendedRules ++ parentRules
    }
  }

  @transient
  override protected[sql] val planner = new SparkPlanner with ExtendedPlanner {
    override def strategies: Seq[Strategy] =
      extensions.flatMap(_.strategies(this)) ++ super.strategies
  }

}

trait ExtendedPlanner {
  self: SQLContext#SparkPlanner =>
  def planLaterExt(p : LogicalPlan) : SparkPlan = self.planLater(p)
}

@DeveloperApi
trait SQLContextExtension {

  def sqlParser : Option[SparkSQLParser] = None

  def functionRegistry : Option[FunctionRegistry] = None

  def resolutionRules(analyzer : Analyzer) : Seq[Rule[LogicalPlan]] = Nil

  def strategies(planner : ExtendedPlanner) : Seq[Strategy] = Nil

  def registerFunctions(registry : FunctionRegistry) : Unit = { }

}