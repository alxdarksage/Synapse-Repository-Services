package org.sagebionetworks.repo.model.dao.table;

import java.io.IOException;
import java.util.ConcurrentModificationException;
import java.util.Map;

import org.sagebionetworks.repo.model.table.CurrentRowCacheStatus;
import org.sagebionetworks.repo.model.table.Row;

/**
 * This is an interface to the row cache.
 */
public interface TableRowCache {
	
	public boolean isEnabled();

	public CurrentRowCacheStatus getLatestCurrentVersionNumber(Long tableId);

	public void setLatestCurrentVersionNumber(CurrentRowCacheStatus oldStatus, Long newLastCurrentVersion2)
			throws ConcurrentModificationException;

	public Map<Long, Long> getCurrentVersionNumbers(Long tableId, Iterable<Long> rowIds);

	public Map<Long, Long> getCurrentVersionNumbers(Long tableId);

	public void updateCurrentVersionNumbers(Long tableId, Map<Long, Long> rowIdVersionNumbers);

	public void removeFromCache(Long tableId);

	public Row getRowFromCache(Long tableId, Long rowId, Long versionNumber) throws IOException;

	public Map<Long, Row> getRowsFromCache(Long tableId, Map<Long, Long> rowIdVersionNumbers) throws IOException;

	public Map<Long, Row> getRowsFromCache(Long tableId, Long version, Iterable<Long> rowsToGet) throws IOException;

	public void putRowInCache(Long tableId, Row row) throws IOException;

	public void putRowsInCache(Long tableId, Iterable<Row> rows) throws IOException;

	public void truncateAllData();
}
