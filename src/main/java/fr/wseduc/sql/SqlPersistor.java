/*
 * Copyright © WebServices pour l'Éducation, 2014
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.wseduc.sql;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.micrometer.backends.BackendRegistries;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.SslMode;
import io.vertx.sqlclient.*;
import io.vertx.sqlclient.desc.ColumnDescriptor;
import org.apache.commons.lang3.tuple.Pair;
import org.vertx.java.busmods.BusModBase;

import java.sql.*;
import java.sql.PreparedStatement;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static fr.wseduc.sql.TimestampEncoderDecoder.encode;
import static fr.wseduc.webutils.Utils.isNotEmpty;
import static java.lang.System.currentTimeMillis;

import io.micrometer.core.instrument.Counter;

public class SqlPersistor extends BusModBase implements Handler<Message<JsonObject>> {

	private static final Logger logger = LoggerFactory.getLogger(SqlPersistor.class);
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SqlPersistor.class);

	private Pattern writingClausesPattern = Pattern.compile(
			"(update\\s+|create\\s+|merge_|delete\\s+|remove\\s+|insert\\s+|alter\\s+|add\\s+|drop\\s+|constraint\\s+|\\s+nextval" +
			"|insert_users_members|insert_group_members|function_|reset_time_slots|delete_trombinoscope_failure|delete_incident)",
			Pattern.CASE_INSENSITIVE);

	private Counter nbRequestsCounter;
  private Timer executionTime;

	private Pool primaryPool;
	private Pool secondaryPool;

	@Override
	public void start(final Promise<Void> startPromise) throws Exception {
		logger.info("Starting mod postgres");
		try {
			super.start(startPromise);
			String primaryUrl = config().getString("url", "postgresql://localhost:5432/test").replaceFirst("^jdbc:", "");
			String secondaryUrl = config().getString("url-slave").replaceFirst("^jdbc:", "");
			String username = config().getString("username", "postgres");
			String password = config().getString("password", "");
			int maxPoolSize = config().getInteger("pool_size", 10);

			// Extract primary database connection options
			PgConnectOptions primaryConnectOptions = PgConnectOptions.fromUri(primaryUrl)
				.setUser(username)
				.setPassword(password)
				//.addProperty("stringtype", "unspecified") // Query parameter
				.setSslMode(SslMode.REQUIRE); ;

			// Configure connection pool
			PoolOptions primaryPoolOptions = new PoolOptions()
				.setMaxSize(maxPoolSize)
				.setName("pg-primary-pool");

			// Initialize primary pool
			primaryPool = Pool.pool(vertx, primaryConnectOptions, primaryPoolOptions);

			if (secondaryUrl != null && !secondaryUrl.isEmpty()) {
				// Extract secondary database connection options
				PgConnectOptions secondaryConnectOptions = PgConnectOptions.fromUri(secondaryUrl)
					.setUser(username)
					.setPassword(password)
					//.addProperty("stringtype", "unspecified") // Query parameter
					.setSslMode(SslMode.REQUIRE);

				// Configure secondary pool
				PoolOptions secondaryPoolOptions = new PoolOptions()
					.setMaxSize(maxPoolSize)
					.setName("pg-secondary-pool");

				// Initialize secondary pool
				secondaryPool = Pool.pool(vertx, secondaryConnectOptions, secondaryPoolOptions);
			}

			vertx.eventBus().consumer(config.getString("address", "sql.persistor"), this);
			startPromise.tryComplete();

			final MeterRegistry registry = BackendRegistries.getDefaultNow();
			if (registry == null) {
				throw new IllegalStateException("micrometer.registries.empty");
			}
			nbRequestsCounter = Counter.builder("pg.persistor.queries")
				.description("number of queries executed by pg persistor")
				.register(registry);
			executionTime = Timer.builder("pg.persistor.exec.time")
				.description("blocking between invocation and execution")
				.publishPercentileHistogram()
				.maximumExpectedValue(Duration.ofSeconds(1L))
				.register(registry);
		} catch(Exception e) {
			logger.error("Error while starting mod postgres", e);
		}
	}

	@Override
	public void stop() throws Exception {
		super.stop();
		if (primaryPool != null) {
			primaryPool.close();
		}
		if(secondaryPool != null) {
			secondaryPool.close();
		}
	}

	@Override
	public void handle(Message<JsonObject> message) {
		final long start = currentTimeMillis();
		nbRequestsCounter.increment();
		String action = message.body().getString("action", "");
		final long startQ = currentTimeMillis();
		switch (action) {
			case "select" :
				doSelect(message);
				break;
			case "insert" :
				doInsert(message);
				break;
			case "prepared" :
				doPrepared(message);
				break;
			case "transaction" :
				doTransaction(message);
				break;
			case "raw" :
				doRaw(message);
				break;
			case "upsert" :
				doUpsert(message);
				break;
			default :
				sendError(message, "invalid.action");
		}
		executionTime.record(currentTimeMillis() - startQ, TimeUnit.MILLISECONDS);
	}

	private Future<JsonObject> doRaw(Message<JsonObject> message) {
		final Future<SqlConnection> connection;
		final String query = message.body().getString("command");
		if (secondaryPool != null && query != null) {
			final Matcher m = writingClausesPattern.matcher(query);
			if (!m.find()) {
				connection = secondaryPool.getConnection();
			} else {
				connection = primaryPool.getConnection();
			}
		} else {
			connection = primaryPool.getConnection();
		}
		return connection
			.compose(conn -> raw(message.body(), conn))
			.onSuccess(result -> {
				if(result != null) {
					sendOK(message, result);
				} else {
					sendError(message, "invalid.query");
				}
			})
			.onFailure(th -> sendError(message, th))
			.onComplete(e -> {
				if(connection.succeeded()) {
					connection.result().close().onFailure(th -> logger.error("Error while closing connection", th));
				}
			});
	}


	private Future<JsonObject> raw(JsonObject json, SqlConnection connection) {
		String query = json.getString("command");
		if (query == null || query.isEmpty()) {
			return null;
		}
		return raw(query, connection);
	}

	private Future<JsonObject> raw(String query, SqlConnection connection) {
		return connection.query(query).execute()
			.compose(result -> {
				JsonObject response;
				if (result.size() > 0) {
					response = buildResults(result); // Handle result rows
				} else {
					response = buildResults(result.rowCount()); // Handle update count
				}
				if (logger.isDebugEnabled()) {
					logger.debug(response.encodePrettily());
				}
				return Future.succeededFuture(response);
			})
			.onFailure(err -> logger.error("Error while executing query : " + query, err));
	}

	private Future<JsonObject> raw(String query) {
		return raw(query, false);
	}

	private Future<JsonObject> raw(String query, boolean checkReadOnly) {
		final Future<SqlConnection> connection;
		if (checkReadOnly && secondaryPool != null && query != null) {
			final Matcher m = writingClausesPattern.matcher(query);
			if (!m.find()) {
				connection = secondaryPool.getConnection();
			} else {
				connection = primaryPool.getConnection();
			}
		} else  {
			connection = primaryPool.getConnection();
		}
		return connection.flatMap(conn -> raw(query, conn));
	}

	private void doTransaction(Message<JsonObject> message) {
		JsonArray statements = message.body().getJsonArray("statements");
		if (statements == null || statements.isEmpty()) {
			sendError(message, "missing.statements");
			return;
		}
		if (logger.isDebugEnabled()) {
			logger.debug("TRANSACTION-JSON: " + statements.encodePrettily());
		}
		primaryPool.getConnection().onFailure(th -> sendError(message, th))
			.flatMap(connection  -> connection.begin().map(tx -> Pair.of(connection, tx)))
			.onSuccess(pair -> {
				final SqlConnection conn = pair.getKey();
				final Transaction tx = pair.getValue();
				executeStatements(statements, conn, 0).onSuccess(results -> {
					tx.commit()
						.onSuccess(e -> sendOK(message, new JsonObject().put("results", results)))
						.onFailure(th -> sendError(message, th));
				})
				.onFailure(th -> sendError(message, th))
				.onComplete(e -> {
					if (conn != null) {
						conn.close().onFailure(th -> logger.error("Error while closing connection after transaction", th));
					}
				})
				.onFailure(th -> sendError(message, th));
		});
	}

	private Future<JsonArray> executeStatements(final JsonArray statements, final SqlConnection connection, final int pos) {
		if(pos >= statements.size()) {
			return Future.succeededFuture(new JsonArray());
		}
		Object s = statements.getValue(pos);
		if (!(s instanceof JsonObject)) return executeStatements(statements, connection, pos + 1);
		JsonObject json = (JsonObject) s;
		String action = json.getString("action", "");
		Future<JsonObject> executor;
		switch (action) {
			case "insert":
				executor = raw(insertQuery(json), connection);
				break;
			case "select":
				executor = raw(selectQuery(json), connection);
				break;
			case "raw":
				executor = raw(json, connection);
				break;
			case "prepared":
				executor = prepared(json, connection);
				break;
			case "upsert":
				executor = raw(upsertQuery(json), connection);
				break;
			default:
				executor = Future.failedFuture("invalid.action");
		}
		return executor.flatMap(result -> {
			return executeStatements(statements, connection, pos + 1).map(results -> {
				return results.add(0, result);
			});
		});
	}

	private Future<Void> doPrepared(Message<JsonObject> message) {
		final Future<SqlConnection> connection;
		final String query = message.body().getString("statement");
		if (secondaryPool != null && isNotEmpty(query)) {
			final Matcher m = writingClausesPattern.matcher(query);
			if (!m.find()) {
				connection = secondaryPool.getConnection();
			} else {
				connection = primaryPool.getConnection();
			}
		} else {
			connection = primaryPool.getConnection();
		}
		return connection.compose(conn -> prepared(message.body(), conn))
			.onSuccess(result -> {
				if (result != null) {
					sendOK(message, result);
				} else {
					logger.error("The query was found to be invalid : " + query);
					sendError(message, "invalid.query");
				}
			})
			.onFailure(th -> sendError(message, th))
			.onComplete(e -> {
				if(connection.succeeded()) {
					connection.result().close();
				}
			})
			.mapEmpty();
	}

	private Future<JsonObject> prepared(final JsonObject json, SqlConnection connection) {
		String query = json.getString("statement");
		JsonArray values = json.getJsonArray("values");
		if (query == null || query.isEmpty() || values == null) {
			return Future.failedFuture("Invalid query or values");
		}

		if (logger.isDebugEnabled()) {
			logger.debug("query : " + query + " - values : " + values.encode());
		}

		Tuple tuple = Tuple.tuple();
		for (int i = 0; i < values.size(); i++) {
			Object val = values.getValue(i);
			if(val instanceof Integer) {
				tuple.addInteger((Integer) val);
			} else {
				tuple.addValue(val);
			}
		}

		return connection.preparedQuery(convertJdbcPlaceholdersToPgSql(query)).execute(tuple)
			.map(resultSet -> {
				JsonObject result;
				if (resultSet.size() > 0) {
					result = buildResults(resultSet);
				} else {
					result = buildResults(resultSet.rowCount());
				}
				if (logger.isDebugEnabled()) {
					logger.debug(result.encodePrettily());
				}
				return result;
			}).onFailure(th -> {
				logger.error("Error while executing query : " + query, th);
			})
			.onComplete(e -> {
				connection.close().onFailure(th -> log.warn("Could not close connection after prepared", th));
			});
	}

	private String convertJdbcPlaceholdersToPgSql(String query) {
		int counter = 1;
		StringBuilder convertedQuery = new StringBuilder();

		for (int i = 0; i < query.length(); i++) {
			if (query.charAt(i) == '?') {
				convertedQuery.append('$').append(counter++);
			} else {
				convertedQuery.append(query.charAt(i));
			}
		}

		return convertedQuery.toString();
	}

	private JsonObject prepared(JsonObject json, Connection connection) throws SQLException {
		String query = json.getString("statement");
		JsonArray values = json.getJsonArray("values");
		if (query == null || query.isEmpty() || values == null) {
			return null;
		}
		if (logger.isDebugEnabled()) {
			logger.debug("query : " + query + " - values : " + values.encode());
		}
		PreparedStatement statement = null;
		ResultSet resultSet = null;
		try {
			statement = connection.prepareStatement(query);
			for (int i = 0; i < values.size(); i++) {
				Object v = values.getValue(i);
				if (v instanceof Integer) {
					statement.setInt(i + 1, (Integer) v);
				} else {
					if (v != null) {
						v = v.toString();
					}
					statement.setObject(i + 1, v);
				}

			}
			JsonObject r;
			if (statement.execute()) {
				resultSet = statement.getResultSet();
				r = buildResults(resultSet);
			} else {
				r = buildResults(statement.getUpdateCount());
			}
			if (logger.isDebugEnabled()) {
				logger.debug(r.encodePrettily());
			}
			return r;
		} finally {
			if (resultSet != null) {
				resultSet.close();
			}
			if (statement != null) {
				statement.close();
			}
		}
	}

	private void doInsert(Message<JsonObject> message) {
		JsonObject json = message.body();
		String query = insertQuery(json);
		if (query == null) {
			sendError(message, "invalid.query");
			return;
		}
			raw(query)
				.onSuccess(result -> sendOK(message, result))
				.onFailure(th -> sendError(message, "Error while doing insert", th));
	}

	private void doUpsert(Message<JsonObject> message) {
		JsonObject json = message.body();
		String query = upsertQuery(json);
		if (query == null) {
			sendError(message, "invalid.query");
			return;
		}
		raw(query)
			.onSuccess(result -> sendOK(message, result))
			.onFailure(th -> sendError(message, "Error while doing upsert", th));
	}

	private String insertQuery(JsonObject json) {
		String table = json.getString("table");
		JsonArray fields = json.getJsonArray("fields");
		JsonArray values = json.getJsonArray("values");
		String returning = json.getString("returning");
		if (table == null || table.isEmpty() || fields == null ||
				fields.size() == 0 || values == null || values.size() == 0) {
			return null;
		}
		StringBuilder sb = new StringBuilder("INSERT INTO ")
				.append(table)
				.append(" (");
		for (Object o : fields) {
			if (!(o instanceof String)) continue;
			sb.append(escapeField((String) o)).append(",");
		}
		sb.deleteCharAt(sb.length()-1);
		sb.append(") VALUES ");
		for (Object row : values) {
			if (row instanceof JsonArray) {
				sb.append("(");
				for (Object o : (JsonArray) row) {
					sb.append(escapeValue(o)).append(",");
				}
				sb.deleteCharAt(sb.length()-1);
				sb.append("),");
			}
		}
		sb.deleteCharAt(sb.length()-1);
		if (returning != null) {
			sb.append(" RETURNING ").append(returning);
		}
		return sb.toString();
	}

	private String upsertQuery(JsonObject json) {
		String table = json.getString("table");
		JsonArray fields = json.getJsonArray("fields");
		JsonArray conflictFields = json.getJsonArray("conflictFields");
		boolean hasConflictFields = conflictFields != null && !conflictFields.isEmpty();
		JsonArray updateFields = json.getJsonArray("updateFields");
		boolean updateOnConflict = updateFields != null && !updateFields.isEmpty();
		JsonArray values = json.getJsonArray("values");
		String returning = json.getString("returning");
		if (table == null || table.isEmpty() || fields == null ||
				fields.size() == 0 || values == null || values.size() == 0 ||
				(!hasConflictFields && updateOnConflict)) {
			return null;
		}
		StringBuilder sb = new StringBuilder("INSERT INTO ")
				.append(table)
				.append(" (");
		for (Object o : fields) {
			if (!(o instanceof String)) continue;
			sb.append(escapeField((String) o)).append(",");
		}
		sb.deleteCharAt(sb.length()-1);
		sb.append(") VALUES ");
		for (Object row : values) {
			if (row instanceof JsonArray) {
				sb.append("(");
				for (Object o : (JsonArray) row) {
					sb.append(escapeValue(o)).append(",");
				}
				sb.deleteCharAt(sb.length()-1);
				sb.append("),");
			}
		}
		sb.deleteCharAt(sb.length()-1);
		sb.append(" ON CONFLICT ");
		if (hasConflictFields) {
			sb.append("(");
			conflictFields.stream().forEach(field -> sb.append(escapeField((String)field)).append(","));
			sb.deleteCharAt(sb.length()-1);
			sb.append(") ");
		}
		if(updateOnConflict){
			sb.append("DO UPDATE SET ");
			updateFields.stream().forEach(field -> sb.append(escapeField((String)field)).append("= EXCLUDED.").append(escapeField((String)field)).append(","));
			sb.deleteCharAt(sb.length()-1);
		} else {
			sb.append("DO NOTHING");
		}
		if (returning != null) {
			sb.append(" RETURNING ").append(returning);
		}
		return sb.toString();
	}

	private void doSelect(Message<JsonObject> message) {
		JsonObject json = message.body();
		String query = selectQuery(json);
		if (query == null) {
			sendError(message, "invalid.query");
			return;
		}
			raw(query, true)
				.onSuccess(result -> sendOK(message, result))
				.onFailure(th -> sendError(message, "Error while doing select", th));
	}

	private String selectQuery(JsonObject json) {
		String table = json.getString("table");
		JsonArray fields = json.getJsonArray("fields");
		if (table == null || table.isEmpty()) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		if (fields != null && fields.size() > 0) {
			sb.append("SELECT ");
			for (Object o : fields) {
				if (!(o instanceof String)) continue;
				sb.append(escapeField(o.toString())).append(",");
			}
			sb.deleteCharAt(sb.length()-1);
			sb.append(" FROM ").append(table);
		} else {
			sb.append("SELECT * FROM ").append(table);
		}
		return sb.toString();
	}

	private JsonObject buildResults(int rows) {
		JsonObject result = new JsonObject();
		result.put("status", "ok");
		result.put("message", "");
		JsonArray fields = new JsonArray();
		JsonArray results = new JsonArray();
		result.put("fields", fields);
		result.put("results", results);
		result.put("rows", rows);
		return result;
	}

	private void transformResultSet(JsonArray results, RowSet<Row> rs) {
		rs.forEach(row -> {
			JsonArray rowJson = new JsonArray();
			results.add(rowJson);
			final List<ColumnDescriptor> columnDescriptors = rs.columnDescriptors();
			// Process each column in the row
			int i = 0;
			for (ColumnDescriptor columnDescriptor : columnDescriptors) {
				Object value = row.getValue(i);
				if(value == null) {
					rowJson.addNull();
					continue;
				}
				switch (columnDescriptor.jdbcType()) {
					case NULL:
						rowJson.addNull();
						break;
					case ARRAY:
						if(value == null) {
							rowJson.addNull();
						} else {
							final JsonArray jsonArray = new JsonArray();
							// TODO implement
							rowJson.add(jsonArray);
						}
						break;
					case DATE:
						if (value instanceof java.time.LocalDate) {
							rowJson.add(value.toString());
						}
						break;
					case TIMESTAMP:
						if (value instanceof java.time.LocalDateTime) {
							java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
							rowJson.add(((java.time.LocalDateTime) value).format(formatter));
						}
						break;
					case BIGINT:
					case INTEGER:
					case SMALLINT:
					case TINYINT:
						rowJson.add(((Number) value).longValue());
						break;
					case REAL:
					case DOUBLE:
					case FLOAT:
						rowJson.add(((Number) value).doubleValue());
						break;
					case BOOLEAN:
						rowJson.add((Boolean) value);
						break;
					case VARCHAR:
					case CHAR:
						rowJson.add(value.toString());
						break;
					case BLOB:
						rowJson.add(value.toString()); // Adjust if binary data handling is required
						break;
					default:
						log.warn("Unhandled type " + columnDescriptor.jdbcType());
						rowJson.add(value.toString()); // Fallback for other types
				}
				i++;
			}
		});
	}

	private void transformResultSet(JsonArray results, ResultSet rs) throws SQLException{

		ResultSetMetaData rsmd = rs.getMetaData();
		int numColumns = rsmd.getColumnCount();

		while(rs.next()) {
			JsonArray row = new JsonArray();
			results.add(row);
			for (int i = 1; i < numColumns + 1; i++) {
				switch (rsmd.getColumnType(i)) {
					case Types.NULL :
						row.add((Object) null);
						break;
					case Types.ARRAY:
						Array arr = rs.getArray(i);
						if(rs.wasNull()){
							row.add((Object) null);
						} else {
							ResultSet arrRs = arr.getResultSet();
							JsonArray jsonArray = new JsonArray();
							transformResultSet(jsonArray, arrRs);
							row.add(jsonArray);
						}
						break;
					case Types.TINYINT:
					case Types.SMALLINT:
					case Types.INTEGER:
					case Types.BIGINT:
						long l = rs.getLong(i);
						if (rs.wasNull()) {
							row.add((Object) null);
						} else {
							row.add(l);
						}
						break;
					case Types.BIT:
						int precision = rsmd.getPrecision(i);
						if (precision != 1) {
							long l1 = rs.getLong(i);
							if (rs.wasNull()) {
								row.add((Object) null);
							} else {
								row.add(String.format("%0" + precision + "d", l1));
							}
						} else {
							boolean b = rs.getBoolean(i);
							if (rs.wasNull()) {
								row.add((Object) null);
							} else {
								row.add(b);
							}
						}
						break;
					case Types.BOOLEAN :
						boolean b = rs.getBoolean(i);
						if (rs.wasNull()) {
							row.add((Object) null);
						} else {
							row.add(b);
						}
						break;
					case Types.BLOB:
						row.add(rs.getBlob(i));
						break;
					case Types.FLOAT:
					case Types.REAL:
					case Types.DOUBLE:
						double d = rs.getDouble(i);
						if (rs.wasNull()) {
							row.add((Object) null);
						} else {
							row.add(d);
						}
						break;
					case Types.NVARCHAR:
					case Types.VARCHAR:
					case Types.LONGNVARCHAR:
					case Types.LONGVARCHAR:
						row.add(rs.getString(i));
						break;
					case Types.DATE:
						row.add(encode(rs.getDate(i)));
						break;
					case Types.TIMESTAMP:
						Timestamp t = rs.getTimestamp(i);
						if (rs.wasNull()) {
							row.add((Object) null);
						} else {
							row.add(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS").format(t));
						}
						break;
					default:
						Object o = rs.getObject(i);
						if (o != null) {
							row.add(rs.getObject(i).toString());
						} else {
							row.add((Object) null);
						}
				}
			}
		}
	}
	private JsonObject buildResults(RowSet<Row> rowSet) {
		JsonObject result = new JsonObject();
		result.put("status", "ok");
		result.put("message", "");

		JsonArray fields = new JsonArray();
		JsonArray results = new JsonArray();
		result.put("fields", fields);
		result.put("results", results);

		// Extract column names
		if (rowSet.columnsNames() != null) {
			rowSet.columnsNames().forEach(fields::add);
		}

		transformResultSet(results, rowSet);

		result.put("rows", results.size());
		return result;
	}


	private JsonObject buildResults(ResultSet rs) throws SQLException {
		JsonObject result = new JsonObject();
		result.put("status", "ok");
		result.put("message", "");
		JsonArray fields = new JsonArray();
		JsonArray results = new JsonArray();
		result.put("fields", fields);
		result.put("results", results);

		ResultSetMetaData rsmd = rs.getMetaData();
		int numColumns = rsmd.getColumnCount();
		for (int i = 1; i < numColumns + 1; i++) {
			fields.add(rsmd.getColumnName(i));
		}

		transformResultSet(results, rs);

		result.put("rows", results.size());
		return result;
	}

	private String escapeField(String str) {
		return "\"" + str.replace("\"", "\"\"") + "\"";
	}

	private String escapeValue(Object v) {
		if (v == null) {
			return "NULL";
		} else if (v instanceof Integer || v instanceof Boolean) {
			return v.toString();
		} else {
			return "'" + v.toString().replace("'", "''") + "'";
		}
	}

}
