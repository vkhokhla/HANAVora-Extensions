package org.apache.spark.sql.catalyst.plans.logical

import java.util.Locale

import org.apache.spark.sql.catalyst.analysis.Resolver
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.execution.LogicalRDD
import org.apache.spark.sql.execution.datasources.IsLogicalRelation
import org.apache.spark.sql.sources.BaseRelation
import org.apache.spark.sql.sources.sql.SqlLikeRelation

case class Hierarchy(
    relation: LogicalPlan,
    childAlias: String,
    parenthoodExpression: Expression,
    searchBy: Seq[SortOrder],
    startWhere: Option[Expression],
    nodeAttribute: Attribute)
  extends UnaryNode {

  /**
   * Calculate an ad-hoc unique identifier of a hierarchy based
   * on its defining clauses.
   */
  // TODO (YH, AC, MC) This is very limited because it takes literal
  // representation of the source table to determine semantic equality
  // of hierarchies, a good approach should rely on semantic equality
  // of source tables (#105063), if we decide not to do this then we
  // should turn off the check of having different hierarchy node columns
  // used in the same UDF.
  lazy val identifier: String = relation.toString()
    .concat(parenthoodExpression.toString())
    .concat("SB")
    .concat(searchBy.mkString("."))
    .concat("SW")
    .concat(startWhere.mkString("."))
    .toLowerCase(Locale.ENGLISH)
    .replaceAll("#\\d+", "")
    .replaceAll("[^a-z0-9]+", "_")

  override def child: LogicalPlan = relation

  override def output: Seq[Attribute] = child.output :+ nodeAttribute

  private def checkDataTypes(): Unit = {
    parenthoodExpression match {
      case be: BinaryExpression if be.resolved && be.left.dataType.sameType(be.right.dataType) =>
      case x if !parenthoodExpression.resolved =>
      case _ =>
        throw new IllegalArgumentException(
          "Hierarchy only supports binary expressions on JOIN PARENT"
        )
    }
  }
  checkDataTypes()

  override lazy val resolved: Boolean = !expressions.exists(!_.resolved) &&
    childrenResolved &&
    parenthoodExpression.resolved &&
    (startWhere.isEmpty || startWhere.get.resolved)
    searchBy.map(_.resolved).forall(_ == true) &&
    nodeAttribute.resolved

  /**
   * When checking missing input attributes, ignore the node attribute
   * (which is generated by this plan) and any attribute in the parenthood
   * expression.
   *
   * XXX: Ignoring the parenthood expression is a hack to avoid more complex
   *      handling of expression IDs for the child relation and its "parent alias".
   */
  override def missingInput: AttributeSet =
    references -- inputSet -- referencesInParenthoodExpressionSet - nodeAttribute

  private[this] def referencesInParenthoodExpressionSet: AttributeSet =
    AttributeSet(parenthoodExpression.collect({ case a: Attribute => a }))

  /**
    * Candidate attributes for resolution in parenthood expression:
    *   - Every attribute in the child relation.
    *   - Every attribute in the child alias with the child alias added as qualifier.
    * For more info, check the JOIN PARENT syntax.
    */
  private[this] def candidateAttributesForParenthoodExpression(): Seq[Attribute] =
    relation.output ++ relation.output.map({
      case attr => Alias(attr, attr.name)(qualifiers = childAlias :: Nil).toAttribute
    })

  /**
    * This is a replacement of the internal [[resolve()]] method which works
    * for parenthood expression.
    */
  private[sql] def resolveParenthoodExpression(nameParts: Seq[String], resolver: Resolver)
    : Option[NamedExpression] =
    resolve(nameParts, candidateAttributesForParenthoodExpression(), resolver)

}
