package org.apache.spark.sql

import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.{Catalog, OverrideCatalog}
import org.apache.spark.sql.catalyst.plans.logical.Subquery
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.sources.Relation

/** Compatibility trait for spark controller. */
@deprecated("Use org.apache.spark.sql.TemporaryFlagCatalog instead")
trait TemporaryFlagProxyCatalog extends OverrideCatalog {
  abstract override def getTables(databaseName: Option[String]): Seq[(String, Boolean)] = {
    val tables = super.getTables(databaseName)
    tables.map {
      case (tableName: String , isTemporary: Boolean) =>
        val tableIdentifier = TableIdentifier(tableName)
        lookupRelation(tableIdentifier) match {
          case Subquery(_, LogicalRelation(relation: Relation, _)) =>
            (tableIdentifier.table, relation.isTemporary)
          case _ => (tableIdentifier.table, isTemporary)
        }
    }
  }
}

