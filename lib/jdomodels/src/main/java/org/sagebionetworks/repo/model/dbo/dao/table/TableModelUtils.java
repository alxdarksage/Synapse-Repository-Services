package org.sagebionetworks.repo.model.dbo.dao.table;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.sagebionetworks.bridge.model.dbo.dao.CsvNullReader;
import org.sagebionetworks.repo.model.dao.table.RowAccessor;
import org.sagebionetworks.repo.model.dao.table.RowHandler;
import org.sagebionetworks.repo.model.dao.table.RowSetAccessor;
import org.sagebionetworks.repo.model.dao.table.TableRowTruthDAO;
import org.sagebionetworks.repo.model.dbo.persistence.table.DBOTableRowChange;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.ColumnType;
import org.sagebionetworks.repo.model.table.IdRange;
import org.sagebionetworks.repo.model.table.Row;
import org.sagebionetworks.repo.model.table.RowReference;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.TableConstants;
import org.sagebionetworks.repo.model.table.TableRowChange;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import au.com.bytecode.opencsv.CSVWriter;

/**
 * Utilities for working with Tables and Row data.
 * 
 * @author jmhill
 * 
 */
public class TableModelUtils {

	private static final String INVALID_VALUE_TEMPLATE = "Value at [%1$s,%2$s] was not a valid %3$s. %4$s";
	private static final String TABLE_SEMAPHORE_KEY_TEMPLATE = "TALBE-LOCK-%1$d";
	
	/**
	 * Delimiter used to list column model IDs as a string.
	 */
	public static final String COLUMN_MODEL_ID_STRING_DELIMITER = ",";
	

	/**
	 * This utility will validate and convert the passed RowSet to an output CSV written to a GZip stream.
	 * 
	 * @param models
	 * @param set
	 * @param out
	 * @param isDeletion
	 * @throws IOException
	 */
	public static void validateAnWriteToCSVgz(List<ColumnModel> models, RowSet set, OutputStream out, boolean isDeletion) throws IOException {
		GZIPOutputStream zipOut = null;
		OutputStreamWriter osw = null;
		CSVWriter csvWriter = null;
		try{
			zipOut = new GZIPOutputStream(out);
			osw = new OutputStreamWriter(zipOut);
			csvWriter = new CSVWriter(osw);
			// Write the data to the the CSV.
			TableModelUtils.validateAndWriteToCSV(models, set, csvWriter, isDeletion);
		}finally{
			if(csvWriter != null){
				csvWriter.flush();
				csvWriter.close();
			}
			if(out != null){
				out.close();
			}
		}
	}
	

	/**
	 * This utility will validate and convert the passed RowSet to an output
	 * CSV.
	 * 
	 * @param models
	 * @param set
	 */
	public static void validateAndWriteToCSV(List<ColumnModel> models,
 RowSet set, CSVWriter out, boolean isDeletion) {
		if (models == null)
			throw new IllegalArgumentException("Models cannot be null");
		validateRowSet(set);
		if (out == null)
			throw new IllegalArgumentException("CSVWriter cannot be null");
		if (models.size() != set.getHeaders().size())
			throw new IllegalArgumentException(
					"RowSet.headers size must be equal to the number of columns in the table.  The table has :"
							+ models.size()
							+ " columns and the passed RowSet.headers has: "
							+ set.getHeaders().size());
		// Now map the index of each column
		Map<String, Integer> columnIndexMap = createColumnIdToIndexMap(set);
		// Process each row
		int count = 0;
		for (Row row : set.getRows()) {
			if (row.getRowId() == null)
				throw new IllegalArgumentException(
						"Row.rowId cannot be null for row number: " + count);
			if (row.getVersionNumber() == null)
				throw new IllegalArgumentException(
						"Row.versionNumber cannot be null for row number: "
								+ count);
			String[] finalRow;
			if (isDeletion) {
				// only output rowId and rowVersion
				finalRow = new String[2];
			} else {
				// First convert the values to
				if (row.getValues() == null)
					throw new IllegalArgumentException("Row " + count
							+ " has null list of values");
				if (row.getValues().size() == 0)
					throw new IllegalArgumentException("Row " + count + " has empty list of values");
				if (models.size() != row.getValues().size())
					throw new IllegalArgumentException(
							"Row.value size must be equal to the number of columns in the table.  The table has :"
									+ models.size()
									+ " columns and the passed Row.value has: "
									+ row.getValues().size()
									+ " for row number: "
									+ count);
				// Convert the values to an array for quick lookup
				String[] values = row.getValues().toArray(
						new String[row.getValues().size()]);
				// Prepare the final array which includes the ID and version number
				// as the first two columns.
				finalRow = new String[values.length + 2];
				// Now process all of the columns as defined by the schema
				for (int i = 0; i < models.size(); i++) {
					ColumnModel cm = models.get(i);
					Integer valueIndex = columnIndexMap.get(cm.getId());
					if (valueIndex == null)
						throw new IllegalArgumentException(
								"The Table's ColumnModels includes: name="
										+ cm.getName()
										+ " with id="
										+ cm.getId()
										+ " but "
										+ cm.getId()
										+ " was not found in the headers of the RowResults");
					// Get the value
					String value = values[valueIndex];
					// Validate the value against the model
					value = validateRowValue(value, cm, i, valueIndex);
					// Add the value to the final
					finalRow[i + 2] = value;
				}
			}
			finalRow[0] = row.getRowId().toString();
			finalRow[1] = row.getVersionNumber().toString();
			out.writeNext(finalRow);
			count++;
		}
	}


	/**
	 * @param set
	 */
	public static void validateRowSet(RowSet set) {
		if (set == null)
			throw new IllegalArgumentException("RowSet cannot be null");
		;
		if (set.getHeaders() == null)
			throw new IllegalArgumentException("RowSet.headers cannot be null");
		if (set.getRows() == null)
			throw new IllegalArgumentException("RowSet.rows cannot be null");
		if (set.getRows().size() < 1)
			throw new IllegalArgumentException(
					"RowSet.rows must contain at least one row.");
	}

	/**
	 * Validate a value
	 * 
	 * @param value
	 * @param cm
	 * @param rowIndex
	 * @param columnIndex
	 * @return
	 */
	public static String validateRowValue(String value, ColumnModel cm,
			int rowIndex, int columnIndex) {
		if (cm == null)
			throw new IllegalArgumentException("ColumnModel cannot be null");
		if (cm.getColumnType() == null)
			throw new IllegalArgumentException(
					"ColumnModel.columnType cannot be null");
		
		// Only strings can have a value that is an empty string. See PLFM-2657
		if("".equals(value)  &&  !ColumnType.STRING.equals(cm.getColumnType())){
			value = null;
		}
		
		// Validate non-null values
		if (value != null) {	
			try {
				switch (cm.getColumnType()) {
				case BOOLEAN:
					boolean boolValue = Boolean.parseBoolean(value);
					return Boolean.toString(boolValue);
				case LONG:
				case FILEHANDLEID:
				case DATE:
					long lv = Long.parseLong(value);
					return Long.toString(lv);
				case DOUBLE:
					double dv = Double.parseDouble(value);
					return Double.toString(dv);
				case STRING:
					if(cm.getMaximumSize() == null) throw new IllegalArgumentException("Strign columns must have a maximum size");
					if (value.length() > cm.getMaximumSize())
						throw new IllegalArgumentException(
								"String exceeds the maximum length of "
										+ cm.getMaximumSize()
										+ " characters. Consider using a FileHandle to store large strings.");
					return value;
				}
				throw new IllegalArgumentException("Unknown ColumModel type: " + cm.getColumnType());
			} catch (Exception e) {
				throw new IllegalArgumentException(String.format(
						INVALID_VALUE_TEMPLATE, rowIndex, columnIndex,
						cm.getColumnType(), e.getLocalizedMessage()));
			}
		} else {
			// the value is null so apply the default if provided
			if (cm.getDefaultValue() != null) {
				value = cm.getDefaultValue();
			}
			return value;
		}
	}
	
	/**
	 * Count all of the empty or invalid rows in the set
	 * @param set
	 */
	public static int countEmptyOrInvalidRowIds(RowSet set){
		validateRowSet(set);
		int count = 0;
		for (Row row : set.getRows()) {
			if(isNullOrInvalid(row.getRowId())){
				count++;
			}
		}
		return count;
	}
	
	/**
	 * Is the row ID null or invalid?
	 * 
	 * @param rowId
	 * @return
	 */
	public static boolean isNullOrInvalid(Long rowId) {
		if(rowId == null) return true;
		return rowId < 0;
	}
	
	/**
	 * Is this a deleted row?
	 * 
	 * @param row
	 * @return
	 */
	public static boolean isDeletedRow(Row row) {
		return row.getValues() == null || row.getValues().size() == 0;
	}

	/**
	 * Assign RowIDs and version numbers to each row in the set according to the passed range.
	 * @param set
	 * @param range
	 */
	public static void assignRowIdsAndVersionNumbers(RowSet set, IdRange range){
		validateRowSet(set);
		Long id = range.getMinimumId();
		for (Row row : set.getRows()) {
			// Set the version number for each row
			row.setVersionNumber(range.getVersionNumber());
			if(isNullOrInvalid(row.getRowId())){
				if(range.getMinimumId() == null){
					throw new IllegalStateException("RowSet required at least one row ID but none were allocated.");
				}
				// This row needs an id.
				row.setRowId(id);
				id++;
				// Validate we have not exceeded the rows
				if(row.getRowId() > range.getMaximumId()){
					throw new IllegalStateException("RowSet required more row IDs than were allocated.");
				}
			}else{
				// Validate the rowId is within range
				if(row.getRowId() > range.getMaximumUpdateId()){
					throw new IllegalArgumentException("Cannot update row: "+row.getRowId()+" because it does not exist.");
				}
			}
		}
	}

	/**
	 * Read the passed CSV into a RowSet.
	 * @param reader
	 * @return
	 * @throws IOException
	 */
	public static List<Row> readFromCSV(CsvNullReader reader) throws IOException {
		if (reader == null)
			throw new IllegalArgumentException("CsvNullReader cannot be null");
		final List<Row> rows = new LinkedList<Row>();
		// Scan the data.
		scanFromCSV(reader,new RowHandler() {
			
			@Override
			public void nextRow(Row row) {
				// Add this to the rows
				rows.add(row);
			}
		});
		return rows;
	}
	
	
	/**
	 * Read the passed CSV into a RowSet.
	 * @param reader
	 * @return
	 * @throws IOException
	 */
	public static void scanFromCSV(CsvNullReader reader, RowHandler handler) throws IOException {
		if (reader == null)
			throw new IllegalArgumentException("CsvNullReader cannot be null");
		String[] rowArray = null;
		while((rowArray = reader.readNext()) != null){
			Row row = new Row();
			if (rowArray.length < 2)
				throw new IllegalArgumentException("Row does not contain at least three columns");
			row.setRowId(Long.parseLong(rowArray[0]));
			row.setVersionNumber(Long.parseLong(rowArray[1]));
			List<String> values = new LinkedList<String>();
			// Add the rest of the values
			for(int i=2; i<rowArray.length; i++){
				values.add(rowArray[i]);
			}
			row.setValues(values);
			// Pass to the handler
			handler.nextRow(row);
		}
	}
	
	/**
	 * Read the passed Gzip CSV into a RowSet.
	 * @param zippedStream
	 * @return
	 * @throws IOException 
	 */
	public static List<Row> readFromCSVgzStream(InputStream zippedStream) throws IOException{
		GZIPInputStream zipIn = null;
		InputStreamReader isr = null;
		CsvNullReader csvReader = null;
		try{
			zipIn = new GZIPInputStream(zippedStream);
			isr = new InputStreamReader(zipIn);
			csvReader = new CsvNullReader(isr);
			return readFromCSV(csvReader);
		}finally{
			if(csvReader != null){
				csvReader.close();
			}
		}
	}
	
	/**
	 * Scan over the passed stream without loading it into memory
	 * @param zippedStream
	 * @param tableId
	 * @param headers
	 * @param handler
	 * @throws IOException
	 */
	public static void scanFromCSVgzStream(InputStream zippedStream, RowHandler handler) throws IOException{
		GZIPInputStream zipIn = null;
		InputStreamReader isr = null;
		CsvNullReader csvReader = null;
		try{
			zipIn = new GZIPInputStream(zippedStream);
			isr = new InputStreamReader(zipIn);
			csvReader = new CsvNullReader(isr);
			scanFromCSV(csvReader, handler);
		}finally{
			if(csvReader != null){
				csvReader.close();
			}
		}
	}
	
	/**
	 * Extract the headers from a list of column Models.s
	 * @param models
	 * @return
	 */
	public static List<String> getHeaders(List<ColumnModel> models){
		if(models == null) throw new IllegalArgumentException("ColumnModels cannot be null");
		List<String> headers = new LinkedList<String>();
		for(ColumnModel model: models){
			if(model.getId() == null) throw new IllegalArgumentException("ColumnModel ID cannot be null");
			headers.add(model.getId());
		}
		return headers;
	}
	
	/**
	 * Create a delimited string of column model IDs.
	 * @param models
	 * @return
	 */
	public static String createDelimitedColumnModelIdString(List<String> headers){
		if(headers == null) throw new IllegalArgumentException("headers cannot be null");
		StringBuilder builder = new StringBuilder();
		boolean first = true;
		for(String id: headers){
			if(!first){
				builder.append(COLUMN_MODEL_ID_STRING_DELIMITER);
			}
			builder.append(id);
			first=false;
		}
		return builder.toString();
	}
	
	/**
	 * Read the list of column model ids from the passed delimited string.
	 * @param in
	 * @return
	 */
	public static List<String> readColumnModelIdsFromDelimitedString(String in){
		if(in == null) throw new IllegalArgumentException("String cannot be null");
		String[] split = in.split(COLUMN_MODEL_ID_STRING_DELIMITER);
		List<String> result = new LinkedList<String>();
		for(String id: split){
			result.add(id);
		}
		return result;
	}
	/**
	 * Convert from the DBO to the DTO
	 * @param dbo
	 * @return
	 */
	public static TableRowChange ceateDTOFromDBO(DBOTableRowChange dbo){
		if(dbo == null) throw new IllegalArgumentException("dbo cannot be null");
		TableRowChange dto = new TableRowChange();
		dto.setTableId(KeyFactory.keyToString(dbo.getTableId()));
		dto.setRowVersion(dbo.getRowVersion());
		dto.setEtag(dbo.getEtag());
		dto.setHeaders(readColumnModelIdsFromDelimitedString(dbo.getColumnIds()));
		dto.setCreatedBy(Long.toString(dbo.getCreatedBy()));
		dto.setCreatedOn(new Date(dbo.getCreatedOn()));
		dto.setBucket(dbo.getBucket());
		dto.setKey(dbo.getKey());
		dto.setRowCount(dbo.getRowCount());
		return dto;
	}
	
	/**
	 * Create a DBO from the DTO
	 * @param dto
	 * @return
	 */
	public static DBOTableRowChange createDBOFromDTO(TableRowChange dto){
		if(dto == null) throw new IllegalArgumentException("dto cannot be null");
		DBOTableRowChange dbo = new DBOTableRowChange();
		dbo.setTableId(KeyFactory.stringToKey(dto.getTableId()));
		dbo.setRowVersion(dto.getRowVersion());
		dbo.setEtag(dto.getEtag());
		dbo.setColumnIds(createDelimitedColumnModelIdString(dto.getHeaders()));
		dbo.setCreatedBy(Long.parseLong(dto.getCreatedBy()));
		dbo.setCreatedOn(dto.getCreatedOn().getTime());
		dbo.setBucket(dto.getBucket());
		dbo.setKey(dto.getKey());
		dbo.setRowCount(dto.getRowCount());
		return dbo;
	}
	
	/**
	 * Convert a list of DTOs from a list of DBOs
	 * @param dbos
	 * @return
	 */
	public static List<TableRowChange> ceateDTOFromDBO(List<DBOTableRowChange> dbos){
		if(dbos == null) throw new IllegalArgumentException("DBOs cannot be null");
		List<TableRowChange> dtos = new LinkedList<TableRowChange>();
		for(DBOTableRowChange dbo: dbos){
			TableRowChange dto = ceateDTOFromDBO(dbo);
			dtos.add(dto);
		}
		return dtos;
	}


	/**
	 * Get the distinct version from the the rows
	 * 
	 * @param rows
	 * @return
	 */
	public static Set<Long> getDistictVersions(List<RowReference> rows) {
		if(rows == null) throw new IllegalArgumentException("rows cannot be null");
		Set<Long> distictVersions = new HashSet<Long>();
		for(RowReference ref: rows){
			distictVersions.add(ref.getVersionNumber());
		}
		return distictVersions;
	}
	
	
	/**
	 * Get the distinct and valid rowIds from the passed rows
	 * @param rows
	 * @return
	 */
	public static Set<Long> getDistictValidRowIds(List<Row> rows) {
		if(rows == null) throw new IllegalArgumentException("rows cannot be null");
		Set<Long> distictRowIds = new HashSet<Long>();
		for(Row ref: rows){
			if(!isNullOrInvalid(ref.getRowId())){
				if (!distictRowIds.add(ref.getRowId())) {
					// the row id is found twice int the same rowset
					throw new IllegalArgumentException("The row id " + ref.getRowId() + " is included more than once in the rowset");
				}
			}
		}
		return distictRowIds;
	}
	
	/**
	 * Convert each passed RowSet into the passed schema and merge all results into a single output set.
	 * @param sets
	 * @param resultSchema
	 * @return
	 */
	public static RowSet convertToSchemaAndMerge(List<RowSet> sets, List<ColumnModel> resultSchema, String tableId){
		// Prepare the final set
		RowSet out = new RowSet();
		out.setTableId(tableId);
		out.setRows(new LinkedList<Row>());
		out.setHeaders(getHeaders(resultSchema));
		// Transform each
		for(RowSet set: sets){
			// Transform each and merge the results
			convertToSchemaAndMerge(set, resultSchema, out);
		}
		return out;
	}
	
	/**
	 * Convert the passed RowSet into the passed schema and append the rows to the passed output set.
	 * @param sets
	 * @param resultSchema
	 * @param sets
	 */
	public static void convertToSchemaAndMerge(RowSet in, List<ColumnModel> resultSchema, RowSet out){
		Map<String, Integer> columnIndexMap = createColumnIdToIndexMap(in);
		// Now convert each row into the requested format.
		// Process each row
		for (Row row : in.getRows()) {
			// First convert the values to
			if (row.getValues() == null) continue;
			Row newRow = convertToSchemaAndMerge(row, columnIndexMap, resultSchema);
			// add the new row to the out set
			out.getRows().add(newRow);
		}
	}
	
	/**
	 * Convert the passed RowSet into the passed schema and append the rows to the passed output set.
	 * @param sets
	 * @param resultSchema
	 * @param sets
	 */
	public static Row convertToSchemaAndMerge(Row row, Map<String, Integer> columnIndexMap, List<ColumnModel> resultSchema) {
		// Convert the values to an array for quick lookup
		String[] values = row.getValues().toArray(new String[row.getValues().size()]);

		// Create the new row
		Row newRow = new Row();
		newRow.setRowId(row.getRowId());
		newRow.setVersionNumber(row.getVersionNumber());
		List<String> newValues = new LinkedList<String>();
		newRow.setValues(newValues);

		// Now process all of the columns as defined by the schema
		for (int i = 0; i < resultSchema.size(); i++) {
			ColumnModel cm = resultSchema.get(i);
			Integer valueIndex = columnIndexMap.get(cm.getId());
			String value = null;
			if (valueIndex == null) {
				// this means this column did not exist when this row as created, so set the value to the default value
				value = cm.getDefaultValue();
			} else {
				// Get the value
				value = values[valueIndex];
			}
			newValues.add(value);
		}
		return newRow;
	}
	
	/**
	 * Calculate the maximum row size for a given schema.
	 * 
	 * @param models
	 * @return
	 */
	public static int calculateMaxRowSize(List<ColumnModel> models){
		if(models == null) throw new IllegalArgumentException("Models cannot be null");
		int size = 0;
		for(ColumnModel cm: models){
			size += calculateMaxSizeForType(cm.getColumnType(), cm.getMaximumSize());
		}
		return size;
	}
	
	/**
	 * Calculate the maximum size in bytes that a column of this type can be when represented as a string. 
	 * @param cm
	 * @return
	 */
	public static int calculateMaxSizeForType(ColumnType type, Long maxSize){
		if(type == null) throw new IllegalArgumentException("ColumnType cannot be null");
		switch (type) {
		case STRING:
			if (maxSize == null)
				throw new IllegalArgumentException("maxSize cannot be null for String types");
			return (int) (ColumnConstants.MAX_BYTES_PER_CHAR_UTF_8 * maxSize);
		case BOOLEAN:
			return ColumnConstants.MAX_BOOLEAN_BYTES_AS_STRING;
		case LONG:
		case DATE:
			return ColumnConstants.MAX_LONG_BYTES_AS_STRING;
		case DOUBLE:
			return ColumnConstants.MAX_DOUBLE_BYTES_AS_STRING;
		case FILEHANDLEID:
			return ColumnConstants.MAX_FILE_HANDLE_ID_BYTES_AS_STRING;
		}
		throw new IllegalArgumentException("Unknown ColumnType: " + type);
	}
	
	/**
	 * Is a request within the maximum number of bytes per request?
	 * 
	 * @param models - The schema of the request.  This determines the maximum number of bytes per row.
	 * @param rowCount - The number of rows requested.
	 * @param maxBytesPerRequest - The limit of the maximum number of bytes per request.
	 */
	public static boolean isRequestWithinMaxBytePerRequest(List<ColumnModel> models, int rowCount, int maxBytesPerRequest){
		// What is the size per row
		int maxBytesPerRow = calculateMaxRowSize(models);
		int neededBytes = rowCount*maxBytesPerRow;
		return neededBytes <= maxBytesPerRequest;
	}
	
	/**
	 * This is the key used to gate access to a single table.
	 * This lock is used both to update the table index and also when a query is run.
	 * @param tableId
	 * @return
	 */
	public static String getTableSemaphoreKey(String tableId){
		if(tableId == null) throw new IllegalArgumentException("TableId cannot be null");
		return String.format(TABLE_SEMAPHORE_KEY_TEMPLATE, KeyFactory.stringToKey(tableId));
	}
	
	/**
	 * Map the column names to the column IDs.
	 * @param columns
	 * @return
	 */
	public static Map<String, Long> createColumnNameToIdMap(List<ColumnModel> columns){
		HashMap<String, Long> map = new HashMap<String, Long>();
		for(ColumnModel cm: columns){
			map.put(cm.getName(), Long.parseLong(cm.getId()));
		}
		return map;
	}

	/**
	 * Map the column id to the column index.
	 * 
	 * @param rowset
	 * @return
	 */
	public static Map<String, Integer> createColumnIdToIndexMap(TableRowChange rowChange) {
		return createColumnIdToIndexMap(rowChange.getHeaders());
	}

	/**
	 * Map the column id to the column index.
	 * 
	 * @param rowset
	 * @return
	 */
	public static Map<String, Integer> createColumnIdToIndexMap(RowSet rowset) {
		return createColumnIdToIndexMap(rowset.getHeaders());
	}

	public static Map<String, Integer> createColumnIdToIndexMap(List<String> headers) {
		Map<String, Integer> columnIndexMap = new HashMap<String, Integer>();
		int index = 0;
		for (String header : headers) {
			columnIndexMap.put(header, index);
			index++;
		}
		return columnIndexMap;
	}

	/**
	 * Map column Id to column Models.
	 * 
	 * @param columns
	 * @return
	 */
	public static Map<Long, ColumnModel> createIDtoColumnModelMap(List<ColumnModel> columns){
		HashMap<Long, ColumnModel>  map = new HashMap<Long, ColumnModel> ();
		for(ColumnModel cm: columns){
			map.put(Long.parseLong(cm.getId()), cm);
		}
		return map;
	}
	
	/**
	 * Map column names to column IDs
	 * @param columns
	 * @return
	 */
	public static Map<String, String> createNameToIDMap(List<ColumnModel> columns){
		HashMap<String, String>  map = new HashMap<String, String> ();
		for(ColumnModel cm: columns){
			map.put(cm.getName(), cm.getId());
		}
		return map;
	}
	
	/**
	 * Given the first row of a CSV create a columnId to Index map.
	 * If the row does not contain the names of the columns then null.
	 * @param rowValues
	 * @param schema
	 * @return
	 */
	public static Map<String, Integer> createColumnIdToIndexMapFromFirstRow(String[] rowValues, List<ColumnModel> schema){
		Map<String, String> nameMap = createNameToIDMap(schema);
		// Are all of the values names?
		for(String value: rowValues){
			// skip reserved column names
			if(TableConstants.isReservedColumnName(value)){
				continue;
			}
			if(!nameMap.containsKey(value)){
				// The values are not column names so this was not a header row.
				return null;
			}
		}
		// Build the map from the names
		Map<String, Integer> columnIdToIndex = new HashMap<String, Integer>(rowValues.length);
		for(int i=0; i<rowValues.length; i++){
			String name = rowValues[i];
			if(TableConstants.isReservedColumnName(name)){
				// Use the name of a reserved column for its name.
				columnIdToIndex.put(name, i);
			}else{
				String columnId = nameMap.get(name);
				columnIdToIndex.put(columnId, i);
			}
		}
		return columnIdToIndex;
	}

	public static RowSetAccessor getRowSetAccessor(final RowSet rowset) {
		return new RowSetAccessor() {

			private Map<String, Integer> columnIdToIndexMap = null;
			private Map<Long, RowAccessor> rowIdToRowMap = null;
			private List<RowAccessor> rows = null;

			@Override
			public Collection<RowAccessor> getRows() {
				if (rows == null) {
					rows = Lists.newArrayListWithCapacity(rowset.getRows().size());
					for (final Row row : rowset.getRows()) {
						rows.add(new RowAccessor() {
							public String getCell(String columnId) {
								Integer index = getColumnIdToIndexMap().get(columnId);
								if (row.getValues() == null || index >= row.getValues().size()) {
									return null;
								}
								return row.getValues().get(index);
							}

							@Override
							public Row getRow() {
								return row;
							}
						});
					}
				}
				return rows;
			}

			protected Map<Long, RowAccessor> getRowIdToRowMap() {
				if (rowIdToRowMap == null) {
					rowIdToRowMap = Maps.newHashMap();
					for (final RowAccessor row : getRows()) {
						if (row.getRow().getRowId() == null) {
							throw new IllegalArgumentException("Cannot handle new rows in RowSetAccessor, access by id");
						}
						rowIdToRowMap.put(row.getRow().getRowId(), row);
					}
				}
				return rowIdToRowMap;
			}

			private Map<String, Integer> getColumnIdToIndexMap() {
				if (columnIdToIndexMap == null) {
					columnIdToIndexMap = TableModelUtils.createColumnIdToIndexMap(rowset);
				}
				return columnIdToIndexMap;
			}
		};
	}
}
