package org.sagebionetworks.repo.web.service.table;

import java.io.IOException;
import java.net.URL;
import java.util.List;

import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.sagebionetworks.repo.model.table.PaginatedColumnModels;
import org.sagebionetworks.repo.model.table.Query;
import org.sagebionetworks.repo.model.table.RowReference;
import org.sagebionetworks.repo.model.table.RowReferenceSet;
import org.sagebionetworks.repo.model.table.RowSelection;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.TableFileHandleResults;
import org.sagebionetworks.repo.model.table.TableUnavilableException;
import org.sagebionetworks.repo.web.NotFoundException;

/**
 * Abstraction for working with TableEntities
 * 
 * @author John
 *
 */
public interface TableServices {

	/**
	 * Create a new ColumnModel.
	 * 
	 * @param userId
	 * @param model
	 * @return
	 * @throws NotFoundException 
	 * @throws DatastoreException 
	 */
	public ColumnModel createColumnModel(Long userId, ColumnModel model) throws DatastoreException, NotFoundException;

	/**
	 * Get a ColumnModel for a given ID
	 * 
	 * @param userId
	 * @param columnId
	 * @return
	 * @throws NotFoundException 
	 * @throws DatastoreException 
	 */
	public ColumnModel getColumnModel(Long userId, String columnId) throws DatastoreException, NotFoundException;
	
	/**
	 * Get the ColumnModels for a TableEntity
	 * @param userId
	 * @param entityId
	 * @return
	 * @throws NotFoundException 
	 * @throws DatastoreException 
	 */
	public PaginatedColumnModels getColumnModelsForTableEntity(Long userId, String entityId) throws DatastoreException, NotFoundException;

	/**
	 * List all of the the ColumnModels.
	 * @param userId
	 * @param prefix
	 * @param limit
	 * @param offset
	 * @return
	 * @throws DatastoreException
	 * @throws NotFoundException
	 */
	public PaginatedColumnModels listColumnModels(Long userId, String prefix, Long limit, Long offset) throws DatastoreException, NotFoundException;
	
	/**
	 * Append rows to a table.
	 * 
	 * @param userId
	 * @param rows
	 * @return
	 * @throws NotFoundException 
	 * @throws DatastoreException 
	 * @throws IOException 
	 */
	public RowReferenceSet appendRows(Long userId, RowSet rows) throws DatastoreException, NotFoundException, IOException;

	/**
	 * Delete rows in a table.
	 * 
	 * @param userId
	 * @param rows
	 * @return
	 * @throws NotFoundException
	 * @throws DatastoreException
	 * @throws IOException
	 */
	public RowReferenceSet deleteRows(Long userId, RowSelection rowsToDelete) throws DatastoreException, NotFoundException, IOException;

	/**
	 * Get the file handles
	 * 
	 * @param userId
	 * @param fileHandlesToFind
	 * @return
	 * @throws IOException
	 * @throws NotFoundException
	 */
	public TableFileHandleResults getFileHandles(Long userId, RowReferenceSet fileHandlesToFind) throws IOException, NotFoundException;

	/**
	 * get file redirect urls for the rows from the column
	 * 
	 * @param userId
	 * @param rowRef
	 * @return
	 * @throws NotFoundException
	 * @throws IOException
	 */
	public URL getFileRedirectURL(Long userId, String tableId, RowReference rowRef, String columnId) throws IOException, NotFoundException;

	/**
	 * get file preview redirect urls for the rows from the column
	 * 
	 * @param userId
	 * @param rowRef
	 * @return
	 * @throws NotFoundException
	 * @throws IOException
	 */
	public URL getFilePreviewRedirectURL(Long userId, String tableId, RowReference rowRef, String columnId) throws IOException,
			NotFoundException;

	/**
	 * Run a query for a user.
	 * @param userId
	 * @param query
	 * @return
	 * @throws NotFoundException 
	 * @throws TableUnavilableException 
	 * @throws DatastoreException 
	 */
	public RowSet query(Long userId, Query query, boolean isConsistent, boolean countOnly) throws NotFoundException, DatastoreException, TableUnavilableException;
}
