// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.catalog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

import com.cloudera.impala.catalog.Function.CompareMode;
import com.cloudera.impala.thrift.TCatalogObjectType;
import com.cloudera.impala.thrift.TDatabase;
import com.cloudera.impala.thrift.TFunctionType;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

/**
 * Internal representation of db-related metadata. Owned by Catalog instance.
 * Not thread safe.
 *
 * The static initialisation method loadDb is the only way to construct a Db
 * object.
 *
 * Tables are stored in a map from the table name to the table object. They may
 * be loaded 'eagerly' at construction or 'lazily' on first reference.
 * Tables are accessed via getTable which may trigger a metadata read in two cases:
 *  * if the table has never been loaded
 *  * if the table loading failed on the previous attempt
 */
public class Db implements CatalogObject {
  private static final Logger LOG = Logger.getLogger(Db.class);
  private final Catalog parentCatalog_;
  private final TDatabase thriftDb_;
  private long catalogVersion_ = Catalog.INITIAL_CATALOG_VERSION;

  // Table metadata cache.
  private final CatalogObjectCache<Table> tableCache_;

  // All of the registered user functions. The key is the user facing name (e.g. "myUdf"),
  // and the values are all the overloaded variants (e.g. myUdf(double), myUdf(string))
  // This includes both UDFs and UDAs. Updates are made thread safe by synchronizing
  // on this map.
  private final HashMap<String, List<Function>> functions_;

  // If true, this database is an Impala system database.
  // (e.g. can't drop it, can't add tables to it, etc).
  private boolean isSystemDb_ = false;

  public Db(String name, Catalog catalog) {
    thriftDb_ = new TDatabase(name.toLowerCase());
    parentCatalog_ = catalog;
    tableCache_ = new CatalogObjectCache<Table>();
    functions_ = new HashMap<String, List<Function>>();
  }

  public void setIsSystemDb(boolean b) { isSystemDb_ = b; }

  /**
   * Creates a Db object with no tables based on the given TDatabase thrift struct.
   */
  public static Db fromTDatabase(TDatabase db, Catalog parentCatalog) {
    return new Db(db.getDb_name(), parentCatalog);
  }

  public boolean isSystemDb() { return isSystemDb_; }
  public TDatabase toThrift() { return thriftDb_; }
  public String getName() { return thriftDb_.getDb_name(); }
  public TCatalogObjectType getCatalogObjectType() {
    return TCatalogObjectType.DATABASE;
  }

  /**
   * Adds a table to the table cache.
   */
  public void addTable(Table table) {
    tableCache_.add(table);
  }

  /**
   * Gets all table names in the table cache.
   */
  public List<String> getAllTableNames() {
    return tableCache_.getAllNames();
  }

  public boolean containsTable(String tableName) {
    return tableCache_.contains(tableName.toLowerCase());
  }

  /**
   * Returns the Table with the given name if present in the table cache or null if the
   * table does not exist in the cache.
   */
  public Table getTable(String tblName) {
    return tableCache_.get(tblName);
  }

  /**
   * Removes the table name and any cached metadata from the Table cache.
   */
  public Table removeTable(String tableName) {
    return tableCache_.remove(tableName.toLowerCase());
  }

  /**
   * Returns all the function signatures in this DB that match the specified
   * function type. If the function type is null, all function signatures are returned.
   */
  public List<String> getAllFunctionSignatures(TFunctionType type) {
    List<String> names = Lists.newArrayList();
    synchronized (functions_) {
      for (List<Function> fns: functions_.values()) {
        for (Function f: fns) {
          if (!f.userVisible()) continue;
          if (type == null ||
              (type == TFunctionType.SCALAR && f instanceof ScalarFunction) ||
              (type == TFunctionType.AGGREGATE && f instanceof AggregateFunction)) {
            names.add(f.signatureString());
          }
        }
      }
    }
    return names;
  }

  /**
   * Returns the number of functions in this database.
   */
  public int numFunctions() {
    synchronized (functions_) {
      return functions_.size();
    }
  }

  /**
   * See comment in Catalog.
   */
  public boolean containsFunction(String name) {
    synchronized (functions_) {
      return functions_.get(name) != null;
    }
  }

  /*
   * See comment in Catalog.
   */
  public Function getFunction(Function desc, Function.CompareMode mode) {
    synchronized (functions_) {
      List<Function> fns = functions_.get(desc.functionName());
      if (fns == null) return null;

      // First check for identical
      for (Function f: fns) {
        if (f.compare(desc, Function.CompareMode.IS_IDENTICAL)) return f;
      }
      if (mode == Function.CompareMode.IS_IDENTICAL) return null;

      // Next check for indistinguishable
      for (Function f: fns) {
        if (f.compare(desc, Function.CompareMode.IS_INDISTINGUISHABLE)) return f;
      }
      if (mode == Function.CompareMode.IS_INDISTINGUISHABLE) return null;

      // Finally check for is_subtype
      for (Function f: fns) {
        if (f.compare(desc, Function.CompareMode.IS_SUPERTYPE_OF)) return f;
      }
    }
    return null;
  }

  public Function getFunction(String signatureString) {
    synchronized (functions_) {
      for (List<Function> fns: functions_.values()) {
        for (Function f: fns) {
          if (f.signatureString().equals(signatureString)) return f;
        }
      }
    }
    return null;
  }

  /**
   * See comment in Catalog.
   */
  public boolean addFunction(Function fn) {
    // TODO: add this to persistent store
    synchronized (functions_) {
      if (getFunction(fn, Function.CompareMode.IS_INDISTINGUISHABLE) != null) {
        return false;
      }
      List<Function> fns = functions_.get(fn.functionName());
      if (fns == null) {
        fns = Lists.newArrayList();
        functions_.put(fn.functionName(), fns);
      }
      return fns.add(fn);
    }
  }

  /**
   * See comment in Catalog.
   */
  public Function removeFunction(Function desc) {
    // TODO: remove this from persistent store.
    synchronized (functions_) {
      Function fn = getFunction(desc, Function.CompareMode.IS_INDISTINGUISHABLE);
      if (fn == null) return null;
      List<Function> fns = functions_.get(desc.functionName());
      Preconditions.checkNotNull(fns);
      fns.remove(fn);
      if (fns.isEmpty()) functions_.remove(desc.functionName());
      return fn;
    }
  }

  /**
   * Removes a Function with the matching signature string. Returns the removed Function
   * if a Function was removed as a result of this call, null otherwise.
   * TODO: Move away from using signature strings and instead use Function IDs.
   */
  public Function removeFunction(String signatureStr) {
    synchronized (functions_) {
      Function targetFn = getFunction(signatureStr);
      if (targetFn != null) return removeFunction(targetFn);
    }
    return null;
  }

  /**
   * Add a builtin with the specified name and signatures to this db.
   */
  public void addScalarBuiltin(boolean udfInterface, String fnName, String symbol,
      boolean varArgs, ColumnType retType, ColumnType ... args) {
    Preconditions.checkState(isSystemDb());
    addBuiltin(ScalarFunction.createBuiltin(
        fnName, Lists.newArrayList(args), varArgs, retType,
        symbol, udfInterface));
  }

  /**
   * Adds a builtin to this database. The function must not already exist.
   */
  public void addBuiltin(Function fn) {
    Preconditions.checkState(isSystemDb());
    Preconditions.checkState(fn != null);
    Preconditions.checkState(getFunction(fn, CompareMode.IS_INDISTINGUISHABLE) == null);
    addFunction(fn);
  }

  /**
   * Returns a map of functionNames to list of (overloaded) functions with that name.
   * This is not thread safe so a higher level lock must be taken while iterating
   * over the returned functions.
   */
  protected HashMap<String, List<Function>> getAllFunctions() {
    return functions_;
  }

  /**
   * Returns all functions that match 'p'.
   */
  public List<Function> getFunctions(Pattern p) {
    List<Function> functions = Lists.newArrayList();
    synchronized (functions_) {
      for (Map.Entry<String, List<Function>> fns: functions_.entrySet()) {
        if (p.matcher(fns.getKey()).matches()) {
          for (Function fn: fns.getValue()) {
            if (fn.userVisible()) functions.add(fn);
          }
        }
      }
    }
    return functions;
  }

  @Override
  public long getCatalogVersion() { return catalogVersion_; }
  @Override
  public void setCatalogVersion(long newVersion) { catalogVersion_ = newVersion; }
  public Catalog getParentCatalog() { return parentCatalog_; }

  @Override
  public boolean isLoaded() { return true; }
}
