package com.ataiva.serengeti.query;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.storage.StorageResponseObject;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class QueryEngine {

    /***
     * Parse query
     * @param query
     * @return List
     */
    public static List<JSONObject> query(String query) {
        if (query.trim().equals("")) {
            return null;
        }

        String[] queries = null;

        if (!query.contains(";")) {
            query = query+";";
        }
        queries = query.split(";");

        List<JSONObject> list = new ArrayList<JSONObject>();
        for (int i=0; i<queries.length; i++) {
            JSONObject n = execute(queries[i].replace("\n", "").trim());
            list.add(i, n);
        }

        return list;
    }

    /***
     * Perform execution of query
     * @param query
     * @return JSONObject
     */
    private static JSONObject execute(String query) {
        QueryResponseObject qro = new QueryResponseObject();

        query = query.toLowerCase().trim();

        qro.query = query;
        qro.executed = false;

        long startTime = System.nanoTime();

        if (query.equals("delete everything")) {
            Serengeti.network.communicateQueryLogAllNodes(new JSONObject() {{
                put("type", "DeleteEverything");
            }}.toString());
            qro.executed = true;
        }

        else if (query.equals("show databases")) {
        // `show databases`

            qro.list = Serengeti.storage.getDatabases();
            qro.executed = true;

        } else if (query.startsWith("show ") && query.endsWith(" tables")) {
        // `show testdb tables`

            String databaseName = query.replace("show ", "").split(" ")[0];
            qro.list = Serengeti.storage.getTables(databaseName);
            qro.executed = true;

        } else if (query.startsWith("create database ")) {
        // `create database testdb`

            String databaseName = query.replace("create database ", "");

            if (Serengeti.storage.databaseExists(databaseName)) {
                qro.error = "Database '" + databaseName + "' already exists";
            } else {
                Serengeti.storage.createDatabase(databaseName);
                qro.executed = true;
            }

        } else if (query.startsWith("drop database ")) {
        // `drop database testdb`

            String databaseName = query.replace("drop database ", "");
            if (Serengeti.storage.databaseExists(databaseName)) {
                Serengeti.storage.dropDatabase(databaseName);
                qro.executed = true;
            } else {
                qro.error = "Database " + databaseName + " does not exist";
            }

        } else if (query.startsWith("create table ")) {
        // `create table testdb.testtable`

            String dbAndTable = query.replace("create table ", "");
            if (dbAndTable.contains(".")) {
                List<String> dbAndTableList = Arrays.asList(dbAndTable.split("\\."));
                if (dbAndTableList.size() == 2) {
                    String databaseName = dbAndTableList.get(0);
                    String tableName = dbAndTableList.get(1);

                    if (Serengeti.storage.tableExists(databaseName, tableName)) {
                        qro.error = "Table '" + tableName + "' already exists";
                    } else {
                        Serengeti.storage.createTable(databaseName, tableName);
                        qro.executed = true;
                    }
                } else {
                    qro.error = "Invalid syntax: create table <db>.<table>";
                }
            } else {
                qro.error = "Invalid syntax: create table <db>.<table>";
            }

        } else if (query.startsWith("drop table ")) {
        // `drop table testdb.testtable`

            List<String> dbAndTable = Arrays.asList(query.replace("drop table ", "").split("\\."));
            String databaseName = dbAndTable.get(0);
            String tableName = dbAndTable.get(1);

            if (Serengeti.storage.tableExists(databaseName, tableName)) {
                Serengeti.storage.dropTable(databaseName, tableName);
                qro.executed = true;
            } else {
                qro.error = "Table "+ tableName + " does not exist";
            }

        } else if (query.startsWith("insert ")) {
        // `insert into testdb.testtable (col1, col2) values('val1', 'val2')`

            List<String> l1 = Arrays.asList(query.split("\\("));

            String dbAndTable = l1.get(0).replace("insert into ", "").trim();

            if (dbAndTable.contains(".")) {
                List<String> dbAndTableList = Arrays.asList(dbAndTable.split("\\."));
                if (dbAndTableList.size() == 2) {
                    String databaseName = dbAndTableList.get(0);
                    String tableName = dbAndTableList.get(1);

                    List<String> keys = Arrays.asList(l1.get(1).replace(") values", "").split(","));
                    List<String> values = Arrays.asList(l1.get(2).replace(")", "").split(","));

                    if (keys.size()!=values.size()) {
                        qro.error = "Invalid syntax: amount of keys and values don't match";
                    } else {
                        JSONObject json = new JSONObject();
                        for (int i = 0; i < keys.size(); i++) {
                            String k = keys.get(i).replaceAll("^\'|\'$", "").trim();
                            String v = values.get(i).replaceAll("^\'|\'$", "").trim();
                            json.put(k, v);
                        }

                        StorageResponseObject sro = Serengeti.storage.insert(databaseName, tableName, json);

//                        Serengeti.indexer.addToQueue(sro);
                        qro.executed = sro.success;
                        qro.primary = sro.primary;
                        qro.secondary = sro.secondary;
                    }

                } else {
                    qro.error = "Invalid syntax: insert into <db>.<table> (col1, col2) values('val1', 'val2')";
                }
            } else {
                qro.error = "Invalid syntax: insert into <db>.<table> (col1, col2) values('val1', 'val2')";
            }

        } else if (query.startsWith("update ")) {
        // `update testdb.testtable set col1='val1', col2='val2' where id='knownid'`

//            List<String> allMatches = new ArrayList<String>();
//            Matcher m = Pattern.compile("(\\s)(.+)( set )(.+)( where )(.+)")
//                    .matcher(query);
//            while (m.find()) {
//                allMatches.serengeti(m.group());
//            }

            List<String> one = Arrays.asList(query.split(" where "));
            String where = one.get(1).trim();

            List<String> two = Arrays.asList(one.get(0).split(" set "));
            List<String> set = Arrays.asList(two.get(1).trim().split(","));

            List<String> dbAndTable = Arrays.asList(two.get(0).replace("update ", "").split("\\."));
            String databaseName = dbAndTable.get(0);
            String tableName = dbAndTable.get(1);

            if (set.size()==0) {
                qro.error = "Invalid syntax: no keys/values to update";
            } else {
                for (int i = 0; i < set.size(); i++) {
                    List<String> kv = Arrays.asList(set.get(i).split("="));
                    List<String> w = Arrays.asList(where.split("="));
                    if (kv.size()==2 && w.size()==2) {

                        String kv1 = kv.get(0).replaceAll("^\'|\'$", "").trim();
                        String kv2 = kv.get(1).replaceAll("^\'|\'$", "").trim();
                        String w1 = w.get(0).replaceAll("^\'|\'$", "").trim();
                        String w2 = w.get(1).replaceAll("^\'|\'$", "").trim();

                        Serengeti.storage.update(databaseName, tableName, kv1, kv2, w1, w2);
                        qro.executed = true;
                    } else {
                        qro.error = "Invalid syntax: Invalid parameter match";
                    }
                }
            }

        } else if (query.startsWith("delete ")) {
        // `delete testdb.testtable where id='knownid'`

            List<String> one = Arrays.asList(query.split(" where "));
            String where = one.get(1).trim();

            List<String> dbAndTable = Arrays.asList(one.get(0).replace("delete", "").split("\\."));
            String databaseName = dbAndTable.get(0).trim();
            String tableName = dbAndTable.get(1).trim();

            List<String> w = Arrays.asList(where.split("="));
            if (w.size()==2) {

                String w1 = w.get(0).replaceAll("^\'|\'$", "").trim();
                String w2 = w.get(1).replaceAll("^\'|\'$", "").trim();

                Serengeti.storage.delete(databaseName, tableName, w1, w2);
                qro.executed = true;
            } else {
                qro.error = "Invalid syntax: Invalid parameter match";
            }

        } else if (query.startsWith("select ")) {
        // `select * from testdb.testtable where id='knownid'`

            if (! query.contains(" where ")) {
                // does not contain a WHERE clause, just return everything according to columns
                List<String> select = Arrays.asList(query.split(" from "));

                List<String> dbAndTable = Arrays.asList(select.get(1).split("\\."));
                if (dbAndTable.size()==2) {
                    String databaseName = dbAndTable.get(0);
                    String tableName = dbAndTable.get(1);

                    String selectWhat = select.get(0).replace("select ", "");

                    qro.list = Serengeti.storage.select(databaseName, tableName, selectWhat, "", "");
                    qro.executed = true;
                } else {
                    qro.error = "Invalid syntax: <db>.<table>";
                }
            } else {
                List<String> one = Arrays.asList(query.split(" where "));
                String where = one.get(1).trim();

                List<String> select = Arrays.asList(one.get(0).split(" from "));

                List<String> dbAndTable = Arrays.asList(select.get(1).split("\\."));
                if (dbAndTable.size()==2) {
                    String databaseName = dbAndTable.get(0);
                    String tableName = dbAndTable.get(1);

                    String selectWhat = select.get(0).replace("select ", "");

                    List<String> w = Arrays.asList(where.split("="));
                    if (w.size() == 2) {

                        String w1 = w.get(0).replaceAll("^\'|\'$", "").trim();
                        String w2 = w.get(1).replaceAll("^\'|\'$", "").trim();

                        qro.list = Serengeti.storage.select(databaseName, tableName, selectWhat, w1, w2);
                        qro.executed = true;
                    } else {
                        qro.error = "Invalid syntax: invalid size";
                    }
                } else {
                    qro.error = "Invalid syntax: <db>.<table>";
                }
            }

        } else {
        // doesn't match anything

            qro.error = "Invalid syntax: not a valid query";
        }

        long endTime = System.nanoTime();
        long timeElapsed = endTime - startTime;


        qro.explain = "";
        qro.runtime = Long.toString(timeElapsed / 1000000); // time in milliseconds

        return qro.json();
    }

}
