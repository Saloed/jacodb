/*
 *  Copyright 2022 UnitTestBot contributors (utbot.org)
 * <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/*
 * This file is generated by jOOQ.
 */
package org.jacodb.impl.storage.jooq.tables


import kotlin.collections.List

import org.jooq.Field
import org.jooq.ForeignKey
import org.jooq.Name
import org.jooq.Record
import org.jooq.Row6
import org.jooq.Schema
import org.jooq.Table
import org.jooq.TableField
import org.jooq.TableOptions
import org.jooq.UniqueKey
import org.jooq.impl.DSL
import org.jooq.impl.Internal
import org.jooq.impl.SQLDataType
import org.jooq.impl.TableImpl
import org.jacodb.impl.storage.jooq.DefaultSchema
import org.jacodb.impl.storage.jooq.keys.FK_METHODPARAMETERS_METHODS_1
import org.jacodb.impl.storage.jooq.keys.FK_METHODPARAMETERS_SYMBOLS_1
import org.jacodb.impl.storage.jooq.keys.PK_METHODPARAMETERS
import org.jacodb.impl.storage.jooq.tables.records.MethodparametersRecord


/**
 * This class is generated by jOOQ.
 */
@Suppress("UNCHECKED_CAST")
open class Methodparameters(
    alias: Name,
    child: Table<out Record>?,
    path: ForeignKey<out Record, MethodparametersRecord>?,
    aliased: Table<MethodparametersRecord>?,
    parameters: Array<Field<*>?>?
): TableImpl<MethodparametersRecord>(
    alias,
    DefaultSchema.DEFAULT_SCHEMA,
    child,
    path,
    aliased,
    parameters,
    DSL.comment(""),
    TableOptions.table()
) {
    companion object {

        /**
         * The reference instance of <code>MethodParameters</code>
         */
        val METHODPARAMETERS = Methodparameters()
    }

    /**
     * The class holding records for this type
     */
    override fun getRecordType(): Class<MethodparametersRecord> = MethodparametersRecord::class.java

    /**
     * The column <code>MethodParameters.id</code>.
     */
    val ID: TableField<MethodparametersRecord, Long?> = createField(DSL.name("id"), SQLDataType.BIGINT, this, "")

    /**
     * The column <code>MethodParameters.access</code>.
     */
    val ACCESS: TableField<MethodparametersRecord, Int?> = createField(DSL.name("access"), SQLDataType.INTEGER.nullable(false), this, "")

    /**
     * The column <code>MethodParameters.index</code>.
     */
    val INDEX: TableField<MethodparametersRecord, Int?> = createField(DSL.name("index"), SQLDataType.INTEGER.nullable(false), this, "")

    /**
     * The column <code>MethodParameters.name</code>.
     */
    val NAME: TableField<MethodparametersRecord, String?> = createField(DSL.name("name"), SQLDataType.VARCHAR(256), this, "")

    /**
     * The column <code>MethodParameters.parameter_class</code>.
     */
    val PARAMETER_CLASS: TableField<MethodparametersRecord, Long?> = createField(DSL.name("parameter_class"), SQLDataType.BIGINT.nullable(false), this, "")

    /**
     * The column <code>MethodParameters.method_id</code>.
     */
    val METHOD_ID: TableField<MethodparametersRecord, Long?> = createField(DSL.name("method_id"), SQLDataType.BIGINT.nullable(false), this, "")

    private constructor(alias: Name, aliased: Table<MethodparametersRecord>?): this(alias, null, null, aliased, null)
    private constructor(alias: Name, aliased: Table<MethodparametersRecord>?, parameters: Array<Field<*>?>?): this(alias, null, null, aliased, parameters)

    /**
     * Create an aliased <code>MethodParameters</code> table reference
     */
    constructor(alias: String): this(DSL.name(alias))

    /**
     * Create an aliased <code>MethodParameters</code> table reference
     */
    constructor(alias: Name): this(alias, null)

    /**
     * Create a <code>MethodParameters</code> table reference
     */
    constructor(): this(DSL.name("MethodParameters"), null)

    constructor(child: Table<out Record>, key: ForeignKey<out Record, MethodparametersRecord>): this(Internal.createPathAlias(child, key), child, key, METHODPARAMETERS, null)
    override fun getSchema(): Schema = DefaultSchema.DEFAULT_SCHEMA
    override fun getPrimaryKey(): UniqueKey<MethodparametersRecord> = PK_METHODPARAMETERS
    override fun getKeys(): List<UniqueKey<MethodparametersRecord>> = listOf(PK_METHODPARAMETERS)
    override fun getReferences(): List<ForeignKey<MethodparametersRecord, *>> = listOf(FK_METHODPARAMETERS_SYMBOLS_1, FK_METHODPARAMETERS_METHODS_1)

    private lateinit var _symbols: Symbols
    private lateinit var _methods: Methods
    fun symbols(): Symbols {
        if (!this::_symbols.isInitialized)
            _symbols = Symbols(this, FK_METHODPARAMETERS_SYMBOLS_1)

        return _symbols;
    }
    fun methods(): Methods {
        if (!this::_methods.isInitialized)
            _methods = Methods(this, FK_METHODPARAMETERS_METHODS_1)

        return _methods;
    }
    override fun `as`(alias: String): Methodparameters = Methodparameters(DSL.name(alias), this)
    override fun `as`(alias: Name): Methodparameters = Methodparameters(alias, this)

    /**
     * Rename this table
     */
    override fun rename(name: String): Methodparameters = Methodparameters(DSL.name(name), null)

    /**
     * Rename this table
     */
    override fun rename(name: Name): Methodparameters = Methodparameters(name, null)

    // -------------------------------------------------------------------------
    // Row6 type methods
    // -------------------------------------------------------------------------
    override fun fieldsRow(): Row6<Long?, Int?, Int?, String?, Long?, Long?> = super.fieldsRow() as Row6<Long?, Int?, Int?, String?, Long?, Long?>
}
