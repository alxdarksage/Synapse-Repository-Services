package org.sagebionetworks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sagebionetworks.bridge.model.data.ParticipantDataColumnDescriptor;
import org.sagebionetworks.bridge.model.data.ParticipantDataColumnType;
import org.sagebionetworks.bridge.model.data.ParticipantDataCurrentRow;
import org.sagebionetworks.bridge.model.data.ParticipantDataDescriptor;
import org.sagebionetworks.bridge.model.data.ParticipantDataDescriptorWithColumns;
import org.sagebionetworks.bridge.model.data.ParticipantDataRepeatType;
import org.sagebionetworks.bridge.model.data.ParticipantDataRow;
import org.sagebionetworks.bridge.model.data.ParticipantDataStatus;
import org.sagebionetworks.bridge.model.data.ParticipantDataStatusList;
import org.sagebionetworks.bridge.model.data.units.Units;
import org.sagebionetworks.bridge.model.data.value.ParticipantDataDatetimeValue;
import org.sagebionetworks.bridge.model.data.value.ParticipantDataDoubleValue;
import org.sagebionetworks.bridge.model.data.value.ParticipantDataEventValue;
import org.sagebionetworks.bridge.model.data.value.ParticipantDataLabValue;
import org.sagebionetworks.bridge.model.data.value.ParticipantDataLongValue;
import org.sagebionetworks.bridge.model.data.value.ParticipantDataStringValue;
import org.sagebionetworks.bridge.model.data.value.ParticipantDataValue;
import org.sagebionetworks.bridge.model.data.value.ValueFactory;
import org.sagebionetworks.bridge.model.timeseries.TimeSeriesTable;
import org.sagebionetworks.bridge.model.versionInfo.BridgeVersionInfo;
import org.sagebionetworks.client.BridgeClient;
import org.sagebionetworks.client.BridgeClientImpl;
import org.sagebionetworks.client.BridgeProfileProxy;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.SynapseAdminClientImpl;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.SynapseClientImpl;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.client.exceptions.SynapseNotFoundException;
import org.sagebionetworks.repo.model.IdList;
import org.sagebionetworks.repo.model.PaginatedResults;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * Run this integration test as a sanity check to ensure our Synapse Java Client is working
 * 
 * @author deflaux
 */
public class IT610BridgeData {

	private static SynapseAdminClient adminSynapse;
	private static BridgeClient bridge = null;
	private static BridgeClient bridgeTwo = null;
	private static List<Long> usersToDelete;

	public static final int PREVIEW_TIMOUT = 10 * 1000;

	public static final int RDS_WORKER_TIMEOUT = 1000 * 60; // One min

	private List<String> handlesToDelete;

	private static BridgeClient createBridgeClient(SynapseAdminClient client) throws Exception {
		SynapseClient synapse = new SynapseClientImpl();
		usersToDelete.add(SynapseClientHelper.createUser(adminSynapse, synapse));

		BridgeClient bridge = new BridgeClientImpl(synapse);
		bridge.setBridgeEndpoint(StackConfiguration.getBridgeServiceEndpoint());

		// Return a proxy
		return BridgeProfileProxy.createProfileProxy(bridge);
	}

	@BeforeClass
	public static void beforeClass() throws Exception {
		usersToDelete = new ArrayList<Long>();

		adminSynapse = new SynapseAdminClientImpl();
		SynapseClientHelper.setEndpoints(adminSynapse);
		adminSynapse.setUserName(StackConfiguration.getMigrationAdminUsername());
		adminSynapse.setApiKey(StackConfiguration.getMigrationAdminAPIKey());

		bridge = createBridgeClient(adminSynapse);
		bridgeTwo = createBridgeClient(adminSynapse);
	}

	@Before
	public void before() throws SynapseException {
		handlesToDelete = new ArrayList<String>();

		for (String id : handlesToDelete) {
			try {
				adminSynapse.deleteFileHandle(id);
			} catch (SynapseNotFoundException e) {
			}
		}
	}

	@After
	public void after() throws Exception {
	}

	@AfterClass
	public static void afterClass() throws Exception {
		for (Long id : usersToDelete) {
			// TODO This delete should not need to be surrounded by a try-catch
			// This means proper cleanup was not done by the test
			try {
				adminSynapse.deleteUser(id);
			} catch (Exception e) {
			}
		}
	}

	@Test
	public void saveDataAndRetrieveNormalized() throws Exception {
		List<ParticipantDataRow> rows = Lists.newArrayList();
		ParticipantDataRow row = new ParticipantDataRow();
		row.setData(Maps.<String,ParticipantDataValue>newHashMap()); 
		ParticipantDataLabValue lab = new ParticipantDataLabValue();
		lab.setValue(1000d);
		lab.setUnits(Units.MILLILITER.getLabels().get(0));
		lab.setMinNormal(500d);
		lab.setMaxNormal(1500d);
		row.getData().put("lab", lab);
		rows.add(row);
		
		ParticipantDataDescriptor descriptor = createDescriptor(false);
		createColumnDescriptor(descriptor, "lab", ParticipantDataColumnType.LAB);
		
		List<ParticipantDataRow> saved = bridge.appendParticipantData(descriptor.getId(), rows);
		Long rowId = saved.get(0).getRowId();
		
		// Milliliters should be converted to liters.
		ParticipantDataRow convertedRow = bridge.getParticipantDataRow(descriptor.getId(), rowId, true); 
		ParticipantDataLabValue savedLab = (ParticipantDataLabValue)convertedRow.getData().get("lab");
		assertEquals(1d, savedLab.getValue(), 0.0);
		assertEquals("L", savedLab.getUnits());
		assertEquals(0.5d, savedLab.getMinNormal(), 0.0);
		assertEquals(1.5d, savedLab.getMaxNormal(), 0.0);
	}
	
	@Test
	public void testGetVersion() throws Exception {
		BridgeVersionInfo versionInfo = bridge.getBridgeVersionInfo();
		assertFalse(versionInfo.getVersion().isEmpty());
	}

	/**
	 * @throws Exception
	 */
	@Test
	public void testCreateAndDeleteParticipantData() throws Exception {
		ParticipantDataDescriptor participantDataDescriptor = createDescriptor(false);

		createColumnDescriptor(participantDataDescriptor, "level", ParticipantDataColumnType.STRING);
		createColumnDescriptor(participantDataDescriptor, "size", ParticipantDataColumnType.STRING);

		String[] headers = { "level", "size" };

		List<ParticipantDataRow> data1 = createRows(headers, null, "5", "200");
		data1 = bridge.appendParticipantData(participantDataDescriptor.getId(), data1);

		List<ParticipantDataRow> data2 = createRows(headers, null, "7", "250", null, "3", "300");
		data2 = bridge.appendParticipantData(participantDataDescriptor.getId(), data2);

		List<ParticipantDataRow> data3 = createRows(headers, null, "5", "200");
		data3 = bridgeTwo.appendParticipantData(participantDataDescriptor.getId(), data3);

		PaginatedResults<ParticipantDataRow> one = bridge.getRawParticipantData(participantDataDescriptor.getId(), Integer.MAX_VALUE, 0, false);
		PaginatedResults<ParticipantDataRow> two = bridgeTwo.getRawParticipantData(participantDataDescriptor.getId(), Integer.MAX_VALUE, 0, false);

		assertEquals(3, one.getResults().size());
		assertEquals(1, two.getResults().size());

		assertEquals(data3, two.getResults());
	}

	@Test
	public void testPaginatedParticipantData() throws Exception {
		ParticipantDataDescriptor participantDataDescriptor = createDescriptor(false);

		createColumnDescriptor(participantDataDescriptor, "level", ParticipantDataColumnType.STRING);
		createColumnDescriptor(participantDataDescriptor, "size", ParticipantDataColumnType.STRING);

		String[] headers = { "level", "size" };

		List<ParticipantDataRow> data1 = createRows(headers, null, "5", "200", null, "6", "200", null, "7", "200");
		data1 = bridge.appendParticipantData(participantDataDescriptor.getId(), data1);

		PaginatedResults<ParticipantDataRow> result;
		result = bridge.getRawParticipantData(participantDataDescriptor.getId(), 1, 0, false);
		assertEquals(1, result.getResults().size());
		assertEquals("5", ((ParticipantDataStringValue) result.getResults().get(0).getData().get("level")).getValue());

		result = bridge.getRawParticipantData(participantDataDescriptor.getId(), 1, 1, false);
		assertEquals(1, result.getResults().size());
		assertEquals("6", ((ParticipantDataStringValue) result.getResults().get(0).getData().get("level")).getValue());

		result = bridge.getRawParticipantData(participantDataDescriptor.getId(), 10, 1, false);
		assertEquals(2, result.getResults().size());
		assertEquals("6", ((ParticipantDataStringValue) result.getResults().get(0).getData().get("level")).getValue());
		assertEquals("7", ((ParticipantDataStringValue) result.getResults().get(1).getData().get("level")).getValue());
	}

	@Test
	public void testReplaceParticipantData() throws Exception {
		ParticipantDataDescriptor participantDataDescriptor = createDescriptor(false);

		createColumnDescriptor(participantDataDescriptor, "level", ParticipantDataColumnType.STRING);
		createColumnDescriptor(participantDataDescriptor, "size", ParticipantDataColumnType.STRING);

		String[] headers = { "level", "size" };

		List<ParticipantDataRow> data1 = createRows(headers, null, "5", "200", null, "6", "200", null, "7", "200");
		data1 = bridge.appendParticipantData(participantDataDescriptor.getId(), data1);

		List<ParticipantDataRow> dataToReplace = createRows(headers, data1.get(1).getRowId(), "8", "200");
		dataToReplace = bridge.updateParticipantData(participantDataDescriptor.getId(), dataToReplace);

		PaginatedResults<ParticipantDataRow> result = bridge.getRawParticipantData(participantDataDescriptor.getId(), Integer.MAX_VALUE, 0, false);

		assertEquals(3, result.getResults().size());
		assertEquals("5", ((ParticipantDataStringValue) result.getResults().get(0).getData().get("level")).getValue());
		assertEquals("8", ((ParticipantDataStringValue) result.getResults().get(1).getData().get("level")).getValue());
		assertEquals("7", ((ParticipantDataStringValue) result.getResults().get(2).getData().get("level")).getValue());
	}

	@Test
	public void testGetCurrentParticipantData() throws Exception {
		ParticipantDataDescriptor participantDataDescriptor = createDescriptor(false);

		createColumnDescriptor(participantDataDescriptor, "level", ParticipantDataColumnType.STRING);
		createColumnDescriptor(participantDataDescriptor, "size", ParticipantDataColumnType.STRING);

		String[] headers = { "level", "size" };

		ParticipantDataStatusList statuses = new ParticipantDataStatusList();
		ParticipantDataStatus update = new ParticipantDataStatus();
		update.setParticipantDataDescriptorId(participantDataDescriptor.getId());
		List<ParticipantDataStatus> updates = Collections.singletonList(update);
		statuses.setUpdates(updates);

		List<ParticipantDataRow> data1 = createRows(headers, null, "5", "200", null, "6", "200", null, "7", "200");
		data1 = bridge.appendParticipantData(participantDataDescriptor.getId(), data1);

		update.setLastEntryComplete(true);
		bridge.sendParticipantDataDescriptorUpdates(statuses);

		ParticipantDataCurrentRow currentRow = bridge.getCurrentParticipantData(participantDataDescriptor.getId(), false);
		assertTrue(currentRow.getCurrentData().getData().isEmpty());
		assertEquals("7", ((ParticipantDataStringValue) currentRow.getPreviousData().getData().get("level")).getValue());

		update.setLastEntryComplete(false);
		bridge.sendParticipantDataDescriptorUpdates(statuses);

		currentRow = bridge.getCurrentParticipantData(participantDataDescriptor.getId(), false);
		assertEquals("6", ((ParticipantDataStringValue) currentRow.getPreviousData().getData().get("level")).getValue());
		assertEquals("7", ((ParticipantDataStringValue) currentRow.getCurrentData().getData().get("level")).getValue());

		List<ParticipantDataRow> dataToReplace = createRows(headers, data1.get(2).getRowId(), "8", "200");
		dataToReplace = bridge.updateParticipantData(participantDataDescriptor.getId(), dataToReplace);

		currentRow = bridge.getCurrentParticipantData(participantDataDescriptor.getId(), false);
		assertEquals("6", ((ParticipantDataStringValue) currentRow.getPreviousData().getData().get("level")).getValue());
		assertEquals("8", ((ParticipantDataStringValue) currentRow.getCurrentData().getData().get("level")).getValue());

		List<ParticipantDataRow> data3 = createRows(headers, null, "9", "200");
		data3 = bridge.appendParticipantData(participantDataDescriptor.getId(), data3);

		currentRow = bridge.getCurrentParticipantData(participantDataDescriptor.getId(), false);
		assertEquals("8", ((ParticipantDataStringValue) currentRow.getPreviousData().getData().get("level")).getValue());
		assertEquals("9", ((ParticipantDataStringValue) currentRow.getCurrentData().getData().get("level")).getValue());

		update.setLastEntryComplete(true);
		bridge.sendParticipantDataDescriptorUpdates(statuses);

		currentRow = bridge.getCurrentParticipantData(participantDataDescriptor.getId(), false);
		assertTrue(currentRow.getCurrentData().getData().isEmpty());
		assertEquals("9", ((ParticipantDataStringValue) currentRow.getPreviousData().getData().get("level")).getValue());
	}

	@Test
	public void testDeleteParticipantDataRows() throws Exception {
		ParticipantDataDescriptor participantDataDescriptor = createDescriptor(false);

		createColumnDescriptor(participantDataDescriptor, "level", ParticipantDataColumnType.STRING);

		String[] headers = { "level" };

		List<ParticipantDataRow> data1 = createRows(headers, null, "5", null, "200", null, "6");
		data1 = bridge.appendParticipantData(participantDataDescriptor.getId(), data1);

		List<Long> rowIds = Lists.newArrayListWithCapacity(data1.size());
		for (ParticipantDataRow row : data1) {
			rowIds.add(row.getRowId());
		}
		IdList idList = new IdList();
		idList.setList(rowIds);
		bridge.deleteParticipantDataRows(participantDataDescriptor.getId(), idList);

		PaginatedResults<ParticipantDataRow> result = bridge.getRawParticipantData(participantDataDescriptor.getId(), 1000, 0, false);
		assertEquals(0, result.getResults().size());
	}

	@Test
	public void testGetCurrentRows() throws Exception {
		ParticipantDataDescriptor participantDataDescriptor = createDescriptor(true);

		createColumnDescriptor(participantDataDescriptor, "event", ParticipantDataColumnType.EVENT);
		createColumnDescriptor(participantDataDescriptor, "level", ParticipantDataColumnType.DOUBLE);

		String[] headers = { "event", "level" };

		List<ParticipantDataRow> data1 = createRows(headers, null, ValueFactory.createEventValue(30000L, null, "a", null), 1.0, null,
				ValueFactory.createEventValue(40000L, 50000L, "b", null), 2.0, null, ValueFactory.createEventValue(20000L, null, "c", null),
				3.0);
		data1 = bridge.appendParticipantData(participantDataDescriptor.getId(), data1);

		List<ParticipantDataRow> currentRows = bridge.getCurrentRows(participantDataDescriptor.getId(), false);
		assertEquals(2, currentRows.size());
		assertEquals(20000L, getEvent(currentRows, 0, "event").getStart().longValue());
		assertEquals(30000L, getEvent(currentRows, 1, "event").getStart().longValue());
	}

	@Test
	public void testGetHistoryRows() throws Exception {
		ParticipantDataDescriptor participantDataDescriptor = createDescriptor(true);

		createColumnDescriptor(participantDataDescriptor, "event", ParticipantDataColumnType.EVENT);
		createColumnDescriptor(participantDataDescriptor, "level", ParticipantDataColumnType.DOUBLE);

		String[] headers = { "event", "level" };

		List<ParticipantDataRow> data1 = createRows(headers, null, ValueFactory.createEventValue(40000L, null, "a", null), 1.0, null,
				ValueFactory.createEventValue(30000L, 40000L, "b", null), 2.0, null,
				ValueFactory.createEventValue(20000L, 30000L, "c", null), 3.0);
		data1 = bridge.appendParticipantData(participantDataDescriptor.getId(), data1);

		List<ParticipantDataRow> currentRows = bridge.getHistoryRows(participantDataDescriptor.getId(), null, null, false);
		assertEquals(3,  currentRows.size());
		assertEquals(20000L, getEvent(currentRows, 0, "event").getStart().longValue());
		assertEquals(30000L, getEvent(currentRows, 1, "event").getStart().longValue());
		assertEquals(40000L, getEvent(currentRows, 2, "event").getStart().longValue());

		currentRows = bridge.getHistoryRows(participantDataDescriptor.getId(), new Date(30000L), null, false);
		assertEquals(2, currentRows.size());
		assertEquals(30000L, getEvent(currentRows, 0, "event").getStart().longValue());
		assertEquals(40000L, getEvent(currentRows, 1, "event").getStart().longValue());

		currentRows = bridge.getHistoryRows(participantDataDescriptor.getId(), null, new Date(35000L), false);
		assertEquals(2, currentRows.size());
		assertEquals(20000L, getEvent(currentRows, 0, "event").getStart().longValue());
		assertEquals(30000L, getEvent(currentRows, 1, "event").getStart().longValue());

		currentRows = bridge.getHistoryRows(participantDataDescriptor.getId(), new Date(25000L), new Date(35000L), false);
		assertEquals(1, currentRows.size());
		assertEquals(30000L, getEvent(currentRows, 0, "event").getStart().longValue());
	}

	private ParticipantDataEventValue getEvent(List<ParticipantDataRow> rows, int index, String column) {
		return ((ParticipantDataEventValue) rows.get(index).getData().get(column));
	}

	@Test
	public void testGetTimeSeries() throws Exception {
		ParticipantDataDescriptor participantDataDescriptor = createDescriptor(false);

		createColumnDescriptor(participantDataDescriptor, "date", ParticipantDataColumnType.DATETIME);
		createColumnDescriptor(participantDataDescriptor, "level", ParticipantDataColumnType.DOUBLE);
		createColumnDescriptor(participantDataDescriptor, "size", ParticipantDataColumnType.LONG);

		String[] headers = { "date", "level", "size" };

		ParticipantDataStatusList statuses = new ParticipantDataStatusList();
		ParticipantDataStatus update = new ParticipantDataStatus();
		update.setParticipantDataDescriptorId(participantDataDescriptor.getId());
		List<ParticipantDataStatus> updates = Collections.singletonList(update);
		statuses.setUpdates(updates);

		List<ParticipantDataRow> data1 = createRows(headers, null, new Date(10000), 5.5, 400L, null, new Date(20000), 6.6, null, null,
				new Date(30000), 7.7, 200L);
		data1 = bridge.appendParticipantData(participantDataDescriptor.getId(), data1);

		TimeSeriesTable timeSeries = bridge.getTimeSeries(participantDataDescriptor.getId(), null, false);
		assertEquals(3, timeSeries.getColumns().size());
		assertEquals(3, timeSeries.getRows().size());
		assertEquals(0, timeSeries.getEvents().size());
		assertEquals(3, timeSeries.getRows().get(0).getValues().size());
		assertEquals(3, timeSeries.getRows().get(1).getValues().size());
		assertEquals(3, timeSeries.getRows().get(2).getValues().size());
		assertEquals(0, timeSeries.getDateIndex().intValue());
		int levelIndex = 1;
		int sizeIndex = 2;
		if (timeSeries.getColumns().get(1).equals("size")) {
			levelIndex = 2;
			sizeIndex = 1;
		}

		assertEquals(new Date(30000).getTime(),
				Long.parseLong(timeSeries.getRows().get(2).getValues().get(timeSeries.getDateIndex().intValue())));
		assertEquals(7.7, Double.parseDouble(timeSeries.getRows().get(2).getValues().get(levelIndex)), 0.0001);
		assertEquals(200.0, Double.parseDouble(timeSeries.getRows().get(2).getValues().get(sizeIndex)), 0.0001);
		assertNull(timeSeries.getRows().get(1).getValues().get(sizeIndex));

		TimeSeriesTable level = bridge.getTimeSeries(participantDataDescriptor.getId(), Lists.newArrayList("level"), false);
		assertEquals(2, level.getColumns().size());
		assertEquals(3, level.getRows().size());
		assertEquals(2, level.getRows().get(0).getValues().size());
		assertEquals(2, level.getRows().get(1).getValues().size());
		assertEquals(2, level.getRows().get(2).getValues().size());
		assertEquals(0, level.getDateIndex().intValue());
		assertEquals(7.7, Double.parseDouble(level.getRows().get(2).getValues().get(1)), 0.0001);

		TimeSeriesTable size = bridge.getTimeSeries(participantDataDescriptor.getId(), Lists.newArrayList("size"), false);
		assertEquals(2, size.getColumns().size());
		assertEquals(3, size.getRows().size());
		assertEquals(2, size.getRows().get(0).getValues().size());
		assertEquals(2, size.getRows().get(1).getValues().size());
		assertEquals(2, size.getRows().get(2).getValues().size());
		assertEquals(0, size.getDateIndex().intValue());
		assertEquals(200.0, Double.parseDouble(size.getRows().get(2).getValues().get(1)), 0.0001);

		TimeSeriesTable namedColumns = bridge.getTimeSeries(participantDataDescriptor.getId(), Lists.newArrayList("level", "size"), false);
		assertEquals(timeSeries, namedColumns);
	}
	
	@Test
	public void testGetEventTimeSeries() throws Exception {
		ParticipantDataDescriptor participantDataDescriptor = createDescriptor(true);

		createColumnDescriptor(participantDataDescriptor, "event", ParticipantDataColumnType.EVENT);
		createColumnDescriptor(participantDataDescriptor, "level", ParticipantDataColumnType.DOUBLE);
		createColumnDescriptor(participantDataDescriptor, "size", ParticipantDataColumnType.LONG);

		String[] headers = { "event", "level", "size" };

		ParticipantDataStatusList statuses = new ParticipantDataStatusList();
		ParticipantDataStatus update = new ParticipantDataStatus();
		update.setParticipantDataDescriptorId(participantDataDescriptor.getId());
		List<ParticipantDataStatus> updates = Collections.singletonList(update);
		statuses.setUpdates(updates);

		List<ParticipantDataRow> data1 = createRows(headers, null, ValueFactory.createEventValue(50000L, 60000L, "a1", "aa"), 5.5, 400L,
				null, ValueFactory.createEventValue(40000L, 50000L, "a2", "aa"), 5.5, 400L, null,
				ValueFactory.createEventValue(10000L, 20000L, "b1", "bb"), 6.61, null, null,
				ValueFactory.createEventValue(30000L, null, "b2", "bb"), 6.63, null, null,
				ValueFactory.createEventValue(20000L, 30000L, "b2", "bb"), 6.62, null, null,
				ValueFactory.createEventValue(20000L, 30000L, "c", null), 7.7, 200L, null,
				ValueFactory.createEventValue(60000L, 30000L, "d", null), 8.8, 300L);
		data1 = bridge.appendParticipantData(participantDataDescriptor.getId(), data1);

		TimeSeriesTable timeSeries = bridge.getTimeSeries(participantDataDescriptor.getId(), null, false);
		assertEquals(3, timeSeries.getColumns().size());
		assertEquals(0, timeSeries.getRows().size());
		assertEquals(4, timeSeries.getEvents().size());
		assertEquals(ValueFactory.createEventValue(10000L, null, "b1", "bb"), timeSeries.getEvents().get(0));
		assertEquals(ValueFactory.createEventValue(20000L, 30000L, "c", null), timeSeries.getEvents().get(1));
		assertEquals(ValueFactory.createEventValue(40000L, 60000L, "a2", "aa"), timeSeries.getEvents().get(2));
		assertEquals(ValueFactory.createEventValue(60000L, 30000L, "d", null), timeSeries.getEvents().get(3));
	}

	@Test
	public void testGetParticipantDataDescriptorWithColumnsAndStatus() throws Exception {
		java.util.Date lastPrompted = new java.util.Date();
		
		ParticipantDataDescriptor descriptor = createDescriptor(false);

		createColumnDescriptor(descriptor, "date", ParticipantDataColumnType.DATETIME);
		createColumnDescriptor(descriptor, "level", ParticipantDataColumnType.DOUBLE);
		createColumnDescriptor(descriptor, "size", ParticipantDataColumnType.LONG);
		
		// Create some data so status can be updated
		String[] headers = { "date", "level", "size" };
		List<ParticipantDataRow> data1 = createRows(headers, null, new Date(10000), 5.5, 400L, null, new Date(20000),
				6.6, null, null, new Date(30000), 7.7, 200L);
		bridge.appendParticipantData(descriptor.getId(), data1);
		
		ParticipantDataStatusList statuses = new ParticipantDataStatusList();
		ParticipantDataStatus status = new ParticipantDataStatus();
		status.setLastEntryComplete(true);
		status.setLastPrompted(lastPrompted);
		status.setParticipantDataDescriptorId(descriptor.getId());
		statuses.setUpdates(Collections.singletonList(status));
		bridge.sendParticipantDataDescriptorUpdates(statuses);
		
		ParticipantDataDescriptorWithColumns descriptorWithColumns = bridge
				.getParticipantDataDescriptorWithColumns(descriptor.getId());
		
		assertEquals(descriptor.getId(), descriptorWithColumns.getDescriptor().getId());
		assertEquals(3, descriptorWithColumns.getColumns().size());
		assertEquals("date", descriptorWithColumns.getColumns().get(0).getName());
		assertEquals(lastPrompted, descriptorWithColumns.getDescriptor().getStatus().getLastPrompted());
		assertTrue(descriptorWithColumns.getDescriptor().getStatus().getLastEntryComplete());
	}

	private void createColumnDescriptor(ParticipantDataDescriptor participantDataDescriptor, String columnName,
			ParticipantDataColumnType columnType) throws SynapseException {
		ParticipantDataColumnDescriptor participantDataColumnDescriptor1 = new ParticipantDataColumnDescriptor();
		participantDataColumnDescriptor1.setParticipantDataDescriptorId(participantDataDescriptor.getId());
		participantDataColumnDescriptor1.setColumnType(columnType);
		participantDataColumnDescriptor1.setName(columnName);
		bridge.createParticipantDataColumnDescriptor(participantDataColumnDescriptor1);
	}

	private ParticipantDataDescriptor createDescriptor(boolean hasEvents) throws SynapseException {
		ParticipantDataDescriptor participantDataDescriptor = new ParticipantDataDescriptor();
		participantDataDescriptor.setName("my-first-participantData-" + System.currentTimeMillis());
		participantDataDescriptor.setRepeatType(ParticipantDataRepeatType.ALWAYS);
		participantDataDescriptor.setDatetimeStartColumnName("date");
		if (hasEvents) {
			participantDataDescriptor.setEventColumnName("event");
		}
		participantDataDescriptor = bridge.createParticipantDataDescriptor(participantDataDescriptor);
		return participantDataDescriptor;
	}

	private List<ParticipantDataRow> createRows(String[] headers, Object... values) {
		List<ParticipantDataRow> data = Lists.newArrayList();
		for (int i = 0; i < values.length; i += headers.length + 1) {
			ParticipantDataRow row = new ParticipantDataRow();
			row.setData(Maps.<String, ParticipantDataValue> newHashMap());
			row.setRowId((Long) values[i]);
			data.add(row);
			for (int j = 0; j < headers.length; j++) {
				if (values[i + j + 1] != null) {
					row.getData().put(headers[j], getValue(values[i + j + 1]));
				}
			}
		}
		return data;
	}

	private ParticipantDataValue getValue(Object value) {
		if (value instanceof Date) {
			ParticipantDataDatetimeValue result = new ParticipantDataDatetimeValue();
			result.setValue(((Date) value).getTime());
			return result;
		}
		if (value instanceof Long) {
			ParticipantDataLongValue result = new ParticipantDataLongValue();
			result.setValue((Long) value);
			return result;
		}
		if (value instanceof Double) {
			ParticipantDataDoubleValue result = new ParticipantDataDoubleValue();
			result.setValue((Double) value);
			return result;
		}
		if (value instanceof String) {
			ParticipantDataStringValue result = new ParticipantDataStringValue();
			result.setValue((String) value);
			return result;
		}
		if (value instanceof ParticipantDataEventValue) {
			return (ParticipantDataEventValue) value;
		}
		throw new IllegalArgumentException(value.getClass().getName());
	}
}
