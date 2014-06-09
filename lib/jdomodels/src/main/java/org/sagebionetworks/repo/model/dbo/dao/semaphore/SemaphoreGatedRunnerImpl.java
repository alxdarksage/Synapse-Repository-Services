package org.sagebionetworks.repo.model.dbo.dao.semaphore;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.PropertyAccessor;
import org.sagebionetworks.ImmutablePropertyAccessor;
import org.sagebionetworks.collections.Maps2;
import org.sagebionetworks.repo.model.dao.semaphore.SemaphoreDao;
import org.sagebionetworks.repo.model.dao.semaphore.SemaphoreGatedRunner;
import org.sagebionetworks.repo.model.exception.LockUnavilableException;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.common.base.Supplier;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * This implementation uses a database backed semaphore and designed to be used with a 
 * cluster of runners.  So when one machine acquires a lock, that lock cannot be acquired by any other 
 * machine in the cluster.  This guarantees that the total number of concurrent runner across the 
 * entire cluster never exceeds the configured maximum number of runners.  Since each machine in the 
 * cluster attempts to acquire locks at a regular interval, lock acquisition should be equally distributed 
 * across the cluster on average (assuming all machines are healthy and responsive).  This is important 
 * because it means we can add more worker machines to the cluster to reduce the load across the entire cluster.
 * For example, if the maximum number of runners is set to 10, and there is only one machine in the cluster,
 * then the one machine will be holding all 10 lock at any given time.  If another machine is added to the 
 * cluster, the second machine will start to acquire some of the locks. Over time, a new equilibrium should
 * be reach where each machine is holding 5 locks at any given time, so the work should be equally divided
 * across the cluster.
 * 
 * @author John
 *
 */
public class SemaphoreGatedRunnerImpl implements SemaphoreGatedRunner {
	
	static private Logger log = LogManager.getLogger(SemaphoreGatedRunnerImpl.class);
	
	/**
	 * This set ensures that the same key is not used by two separate runners
	 */
	private static Map<Object, Set<String>> USED_KEY_SET = Maps2.createSupplierHashMap(new Supplier<Set<String>>() {
		@Override
		public Set<String> get() {
			return Sets.newHashSet();
		}
	});
	/**
	 * The maximum number of characters allowed for a semaphore key. 
	 */
	public static int MAX_KEY_LENGTH = 30;
	/**
	 * The lock timeout cannot be set to less then 10 seconds. 
	 */
	public static long MIN_TIMEOUT_MS = 10*1000;
	
	private static String KEY_NUM_DELIMITER = "-";
	
	@Autowired
	private SemaphoreDao semaphoreDao;
	private String semaphoreKey;
	private PropertyAccessor maxNumberRunners;
	private Runnable runner;
	private Random randomGen = new Random(System.currentTimeMillis());;
	private long timeoutMS;
	
	/**
	 * Used for mock testing.
	 * 
	 * @param semaphoreDao
	 */
	public void setSemaphoreDao(SemaphoreDao semaphoreDao) {
		this.semaphoreDao = semaphoreDao;
	}

	/**
	 * Injected via Spring.
	 * @param semaphoreKey
	 */
	public void setSemaphoreKey(String semaphoreKey) {
		if(semaphoreKey == null) throw new IllegalArgumentException("semaphoreKey cannot be null");
		if(semaphoreKey.length() > MAX_KEY_LENGTH) throw new IllegalArgumentException("semaphoreKey cannot be longer than "+MAX_KEY_LENGTH+" characters");
		// This checks to make sure that we don't use the same key twice. For testing, we reload the context multiple
		// times, which leads to these beans being recreated multiple times. This check uses a singleton bean to make
		// sure we only check for duplicates within a single bean context and not across bean contexts.
		if (!USED_KEY_SET.get(semaphoreDao).add(semaphoreKey)) {
			throw new IllegalArgumentException("The key: '" + semaphoreKey + "' is already in use. Duplicate key name?");
		}
		this.semaphoreKey = semaphoreKey;
	}

	/**
	 * Injected via Spring
	 * @param maxNumberRunners The maximum number of runners of this type.  This gate will guarantee that there are never more than this number of 
	 * runners (inclusive) concurrently running across the entire cluster.  Set this to a number less than one to disable this runner.
	 */
	public void setMaxNumberRunners(int maxNumberRunners) {
		this.maxNumberRunners = new ImmutablePropertyAccessor(maxNumberRunners);
	}

	/**
	 * Injected via Spring
	 * 
	 * @param maxNumberRunners The maximum number of runners of this type. This gate will guarantee that there are never
	 *        more than this number of runners (inclusive) concurrently running across the entire cluster. Set this to a
	 *        number less than one to disable this runner.
	 */
	public void setMaxNumberRunnersAccessor(PropertyAccessor maxNumberRunners) {
		this.maxNumberRunners = maxNumberRunners;
	}

	/**
	 * Injected via Spring
	 * 
	 * @param runner When a lock is acquired, the run() of this runner will be called.
	 */
	public void setRunner(Runnable runner) {
		if(runner == null) throw new IllegalArgumentException("Runner cannot be null");
		this.runner = runner;
	}

	/**
	 * Injected via Spring
	 * @param timeoutMS
	 */
	public void setTimeoutMS(long timeoutMS) {
		if(timeoutMS < MIN_TIMEOUT_MS) throw new IllegalArgumentException("The lock timeout is below the minimum timeout of "+MIN_TIMEOUT_MS+" MS");
		this.timeoutMS = timeoutMS;
	}

	@Override
	public void attemptToRun() {
		if(this.semaphoreKey == null) throw new IllegalArgumentException("semaphoreKey cannot be null");
		if(this.semaphoreDao == null) throw new IllegalArgumentException("semaphoreDao cannot be null");
		if(this.runner == null) throw new IllegalArgumentException("Runner cannot be null");
		if(this.timeoutMS < MIN_TIMEOUT_MS) throw new IllegalArgumentException("The lock timeout is below the minimum timeout of "+MIN_TIMEOUT_MS+" MS");
		// do nothing if the max number of of runner is less than one
		if (maxNumberRunners.getInteger() < 1) {
			if(log.isDebugEnabled()){
				log.debug("Max number of runners is less than one so the runner will not be run");
			}
			return;
		}
		// randomly generate a lock number to attempt
		int lockNumber = randomGen.nextInt(maxNumberRunners.getInteger());
		String key = generateKeyForLockNumber(lockNumber, null);
		String token = semaphoreDao.attemptToAcquireLock(key, timeoutMS);
		if(token != null){
			try{
				// Make a run
				runner.run();
			}catch(Exception e){
				log.error("runner failed: ", e);
			}finally{
				semaphoreDao.releaseLock(key, token);
			}
		}
	}

	@Override
	public <T> T attemptToRunAllSlots(Callable<T> task, String extraKey) throws Exception {
		if (this.semaphoreKey == null)
			throw new IllegalArgumentException("semaphoreKey cannot be null");
		if (this.semaphoreDao == null)
			throw new IllegalArgumentException("semaphoreDao cannot be null");
		if (this.runner != null)
			throw new IllegalArgumentException("Runner should not be set");
		if (this.timeoutMS < MIN_TIMEOUT_MS)
			throw new IllegalArgumentException("The lock timeout is below the minimum timeout of " + MIN_TIMEOUT_MS + " MS");
		// do nothing if the max number of of runner is less than one
		if (maxNumberRunners.getInteger() < 1) {
			if (log.isDebugEnabled()) {
				log.debug("Max number of runners is less than one so the runner will not be run");
			}
			return null;
		}

		List<String> allLockKeys = getAllLockKeys(extraKey);
		// randomly shuffle, so not all machines will try in the same order
		Collections.shuffle(allLockKeys, randomGen);

		for (String key : allLockKeys) {
			String token = semaphoreDao.attemptToAcquireLock(key, timeoutMS);
			if (token != null) {
				try {
					return task.call();
				} finally {
					semaphoreDao.releaseLock(key, token);
				}
			}
		}
		throw new LockUnavilableException("No empty slot available");
	}

	/**
	 * For test cleanup only.
	 */
	void clearKeys(){
		USED_KEY_SET.clear();
	}
	
	private String generateKeyForLockNumber(int lockNumber, String extraKey) {
		return semaphoreKey + (extraKey == null ? "" : KEY_NUM_DELIMITER) + (extraKey == null ? "" : extraKey) + KEY_NUM_DELIMITER
				+ lockNumber;
	}
	
	@Override
	public List<String> getAllLockKeys(String extraKey) {
		int size = maxNumberRunners.getInteger();
		List<String> keys = Lists.newArrayListWithCapacity(size);
		for (int i = 0; i < size; i++) {
			keys.add(generateKeyForLockNumber(i, extraKey));
		}
		return keys;
	}
}
