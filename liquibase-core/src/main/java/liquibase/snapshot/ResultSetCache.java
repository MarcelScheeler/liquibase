package liquibase.snapshot;

import liquibase.CatalogAndSchema;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import liquibase.executor.jvm.ColumnMapRowMapper;
import liquibase.executor.jvm.RowMapperResultSetExtractor;
import liquibase.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

class ResultSetCache {
    private int timesSingleQueried = 0;
    private boolean didBulkQuery = false;

    private Map<String, Map<String, List<CachedRow>>> cacheBySchema = new HashMap<String, Map<String, List<CachedRow>>>();

    private Map<String, Object> info = new HashMap<String, Object>();

    public List<CachedRow> get(ResultSetExtractor resultSetExtractor) throws DatabaseException {
        try {
            String wantedKey = resultSetExtractor.wantedKeyParameters().createParamsKey(resultSetExtractor.database);

            String schemaKey = resultSetExtractor.wantedKeyParameters().createSchemaKey(resultSetExtractor.database);

            Map<String, List<CachedRow>> cache = cacheBySchema.get(schemaKey);
            if (cache == null ) {
                cache = new HashMap<String, List<CachedRow>>();
                cacheBySchema.put(schemaKey, cache);
            }

            if (cache.containsKey(wantedKey)) {
                return cache.get(wantedKey);
            }

            if (didBulkQuery) {
                return new ArrayList<CachedRow>();
            }

            List<CachedRow> results;
            if (resultSetExtractor.shouldBulkSelect(this)) {
                cache.clear(); //remove any existing single fetches that may be duplicated
                results = extract(resultSetExtractor.bulkFetch());
                didBulkQuery = true;
            } else {
                timesSingleQueried++;
                results = extract(resultSetExtractor.fastFetch());
            }

            for (CachedRow row : results) {
                for (String rowKey : resultSetExtractor.rowKeyParameters(row).getKeyPermutations()) {
                    if (!cache.containsKey(rowKey)) {
                        cache.put(rowKey, new ArrayList<CachedRow>());
                    }
                    cache.get(rowKey).add(row);
                }
            }

            List<CachedRow> returnList = cache.get(wantedKey);
            if (returnList == null) {
                returnList = new ArrayList<CachedRow>();
            }
            return returnList;




        } catch (SQLException e) {
            throw new DatabaseException(e);
        }
    }

    private List<CachedRow> extract(ResultSet resultSet) throws SQLException {
        List<Map> result;
        List<CachedRow> returnList = new ArrayList<CachedRow>();
        try {
            result = (List<Map>) new RowMapperResultSetExtractor(new ColumnMapRowMapper()).extractData(resultSet);

            for (Map row : result) {
                returnList.add(new CachedRow(row));
            }
        } finally {
            resultSet.close();
        }
        return returnList;
    }

    public <T> T getInfo(String key, Class<T> type) {
        return (T) info.get(key);
    }

    public void putInfo(String key, Object value) {
        info.put(key, value);
    }

    public static class RowData {
        private Database database;
        private String[] parameters;
        private String catalog;
        private String schema;

        private String[] keyPermutations;

        protected RowData(String catalog, String schema, Database database, String... parameters) {
            this.database = database;
            this.catalog = catalog;
            this.schema = schema;

            this.parameters = parameters;
        }

        public String[] getKeyPermutations() {
            if (keyPermutations == null) {
                this.keyPermutations = permutations(parameters);

            }
            return keyPermutations;
        }

        protected String[] permutations(String[] params) {
            return permute(params, 0);
        }

        private String[] permute(String[] params, int fromIndex) {
            String[] nullVersion = Arrays.copyOf(params, params.length);
            nullVersion[fromIndex] = null;
            if (params.length == fromIndex + 1) {
                return new String[] {
                        createKey(database, params),
                        createKey(database, nullVersion)
                };
            } else {
                List<String> permutations = new ArrayList<String>();

                Collections.addAll(permutations, permute(params, fromIndex + 1));
                Collections.addAll(permutations, permute(nullVersion, fromIndex + 1));

                return permutations.toArray(new String[permutations.size()]);
            }
        }

        public String createSchemaKey(Database database) {
            if (!database.supportsCatalogs() && ! database.supportsSchemas()) {
                return "all";
            } else if (database.supportsCatalogs() && database.supportsSchemas()) {
                return (catalog+"."+schema).toLowerCase();
            } else {
                if (catalog == null && schema != null) {
                    return schema.toLowerCase();
                } else {
                    if (catalog == null) {
                        return "all";
                    }
                    return catalog.toLowerCase();
                }
            }
        }

        public String createKey(Database database, String... params) {
            String key = StringUtils.join(params, ":");
            if (!database.isCaseSensitive()) {
                return key.toLowerCase();
            }
            return key;
        }

        public String createParamsKey(Database database) {
            return createKey(database, parameters);
        }
    }

    public abstract static class ResultSetExtractor {

        private final Database database;

        public ResultSetExtractor(Database database) {
            this.database = database;
        }

        public abstract ResultSet fastFetch() throws SQLException, DatabaseException;
        public abstract ResultSet bulkFetch() throws SQLException, DatabaseException;

        boolean shouldBulkSelect(ResultSetCache resultSetCache) {
            return resultSetCache.timesSingleQueried >= 3;
        }

        ResultSet executeQuery(String sql, Database database) throws DatabaseException, SQLException {
            Statement statement = ((JdbcConnection) database.getConnection()).createStatement();
                return statement.executeQuery(sql);

        }

        public boolean equals(Object expectedValue, Object foundValue) {
            return equals(expectedValue, foundValue, true);
        }

        public boolean equals(Object expectedValue, Object foundValue, boolean equalIfEitherNull) {
            if (expectedValue == null && foundValue == null) {
                return true;
            }
            if (expectedValue == null || foundValue == null) {
                return equalIfEitherNull;
            }

            return expectedValue.equals(foundValue);
        }


        public abstract RowData rowKeyParameters(CachedRow row);

        public abstract RowData wantedKeyParameters();
    }

}