package org.sagebionetworks.search;

import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.StackConfiguration;
import org.springframework.beans.factory.annotation.Autowired;

import com.amazonaws.services.cloudsearch.AmazonCloudSearchClient;
import com.amazonaws.services.cloudsearch.model.AccessPoliciesStatus;
import com.amazonaws.services.cloudsearch.model.CreateDomainRequest;
import com.amazonaws.services.cloudsearch.model.DefineIndexFieldRequest;
import com.amazonaws.services.cloudsearch.model.DeleteIndexFieldRequest;
import com.amazonaws.services.cloudsearch.model.DescribeDomainsRequest;
import com.amazonaws.services.cloudsearch.model.DescribeDomainsResult;
import com.amazonaws.services.cloudsearch.model.DescribeIndexFieldsRequest;
import com.amazonaws.services.cloudsearch.model.DescribeIndexFieldsResult;
import com.amazonaws.services.cloudsearch.model.DescribeServiceAccessPoliciesRequest;
import com.amazonaws.services.cloudsearch.model.DescribeServiceAccessPoliciesResult;
import com.amazonaws.services.cloudsearch.model.DomainStatus;
import com.amazonaws.services.cloudsearch.model.IndexDocumentsRequest;
import com.amazonaws.services.cloudsearch.model.IndexField;
import com.amazonaws.services.cloudsearch.model.IndexFieldStatus;
import com.amazonaws.services.cloudsearch.model.UpdateServiceAccessPoliciesRequest;

public class SearchDomainSetupImpl implements SearchDomainSetup {

	private static final String POLICY_TEMPLATE = "{\"Statement\": [{\"Effect\":\"Allow\", \"Action\": \"*\", \"Resource\": \"%1$s\", \"Condition\": { \"IpAddress\": { \"aws:SourceIp\": [\"%3$s\"] } }}, {\"Effect\":\"Allow\", \"Action\": \"*\", \"Resource\": \"%2$s\", \"Condition\": { \"IpAddress\": { \"aws:SourceIp\": [\"%3$s\"] } }} ] }";

	private static final String SEARCH_DOMAIN_NAME_TEMPLATE = "%1$s-%2$s-sagebase-org";
	private static final String CLOUD_SEARCH_API_VERSION = "2011-02-01";
	private static final String SEARCH_ENDPOINT_TEMPALTE = "http://%1$s/"+ CLOUD_SEARCH_API_VERSION + "/search";
	private static final String DOCUMENT_ENDPOINT_TEMPALTE = "httpS://%1$s/"+ CLOUD_SEARCH_API_VERSION + "/documents/batch";

	private static final String SEARCH_DOMAIN_NAME = String.format(SEARCH_DOMAIN_NAME_TEMPLATE, StackConfiguration.getStack(),	StackConfiguration.getStackInstance());

	static private Logger log = LogManager.getLogger(SearchDaoImpl.class);
	
	/**
	 * Used to ensure we only setup the search index once.
	 */
	private static volatile Boolean INITIALZED = null;

	@Autowired
	AmazonCloudSearchClient awsSearchClient;
	
	boolean isSearchEnabled;
	
	@Override
	public boolean isSearchEnabled() {
		return isSearchEnabled;
	}

	/**
	 * Injected via Spring
	 * @param isSearchEnabled
	 */
	public void setSearchEnabled(boolean isSearchEnabled) {
		this.isSearchEnabled = isSearchEnabled;
	}

	/**
	 * Spring will call this method when the bean is first initialize.
	 * 
	 * @throws InterruptedException
	 * @throws UnknownHostException
	 */
	public void initialize() throws InterruptedException, UnknownHostException {
		if(!isSearchEnabled()){
			log.info("Search is disabled");
			return;
		}
		log.info("initialize...");
		long start = System.currentTimeMillis();
		// Do we have a search index?
		String domainName = getSearchDomainName();
		log.info("Search domain name: " + domainName);
		// If the domain is currently processing then wait for it.
		// Note: We need to wait when the search domain is deleted before we can
		// re-create it.
		waitForDomainProcessing(domainName);
		oneTimeOnlyInitailze(domainName);
		long elapse = System.currentTimeMillis() - start;
		log.info(String.format("Finished initializing search index: Elapse time: %1$tM:%1$tS:%1$tL (Min:Sec:MS)",	elapse));
	}

	/**
	 * @param domainName
	 * @throws InterruptedException
	 */
	public void oneTimeOnlyInitailze(String domainName)	throws InterruptedException {
		if(INITIALZED == null){
			// Create the domain it it does not already exist.
			createDomainIfNeeded(domainName);
			// Set the policy.
			setPolicyIfNeeded(domainName);
			// Define the schema
			defineAndValidateSchema(domainName);
			// Run indexing if needed
			runIndexIfNeeded(domainName);
			// Now wait for the domain if needed
			waitForDomainProcessing(domainName);
			INITIALZED = new Boolean(true);
		}
	}

	/**
	 * @param domainName
	 */
	public void setPolicyIfNeeded(String domainName) {
		DescribeServiceAccessPoliciesResult dsapr = awsSearchClient
				.describeServiceAccessPolicies(new DescribeServiceAccessPoliciesRequest()
						.withDomainName(domainName));
		DomainStatus status = getDomainStatus(domainName);
		// Set the policy.
		// Until we figure out a better plan, we are opening this up to 0.0.0.0
		String policyJson = String.format(POLICY_TEMPLATE, status
				.getDocService().getArn(), status.getSearchService().getArn(),"0.0.0.0/0");
		log.debug("Expected Policy: " + policyJson);
		if (!policyJson.equals(dsapr.getAccessPolicies().getOptions())) {
			log.info("Updateing the Search Access policy as it does not match the expected policy");
			// Add the policy.
			awsSearchClient.updateServiceAccessPolicies(new UpdateServiceAccessPoliciesRequest().withDomainName(domainName).withAccessPolicies(policyJson));
		} else {
			log.info("Search Access policy is already set.");
		}
	}

	/**
	 * If the passed domain name does not exist, it will be created.
	 * 
	 * @param domainName
	 * @throws InterruptedException
	 */
	public void createDomainIfNeeded(String domainName)
			throws InterruptedException {
		DescribeDomainsResult result = awsSearchClient
				.describeDomains(new DescribeDomainsRequest()
						.withDomainNames(domainName));
		if (result.getDomainStatusList().size() < 1) {
			log.info("Search domain does not exist for: " + domainName
					+ ". A new search domain will be created.");
			// Create the search domain.
			awsSearchClient.createDomain(new CreateDomainRequest()
					.withDomainName(domainName));
		}
	}

	/**
	 * Run indexing if needed.
	 * 
	 * @param domainName
	 */
	private void runIndexIfNeeded(String domainName) {
		DomainStatus status = getDomainStatus(domainName);
		if (status.isRequiresIndexDocuments()) {
			log.info("Need to run indexing on the search domain...");
			awsSearchClient.indexDocuments(new IndexDocumentsRequest()
					.withDomainName(domainName));
		}
	}

	/**
	 * Define and validate the schema.
	 * 
	 * @param domainName
	 */
	private void defineAndValidateSchema(String domainName) {
		// Now make sure all of the fields are configured.
		DescribeIndexFieldsResult difrr = awsSearchClient.describeIndexFields(new DescribeIndexFieldsRequest().withDomainName(domainName));
		// Map all of the existing fields
		Map<String, IndexField> currentFieldsMap = new HashMap<String, IndexField>();
		for(IndexFieldStatus status: difrr.getIndexFields()){
			IndexField field = status.getOptions();
			currentFieldsMap.put(field.getIndexFieldName(), field);
		}
		// The the expected schema.
		List<IndexField> indexList = SearchSchemaLoader.loadSearchDomainSchema();
		for (IndexField field : indexList) {
			// Determine if this field already exists
			IndexField currentField = currentFieldsMap.get(field.getIndexFieldName());
			if (currentField == null) {
				// We need to create it.
				log.info("IndexField: " + field.getIndexFieldName()
						+ " does not exist, so it will be created...");
				// Create the field
				awsSearchClient.defineIndexField(new DefineIndexFieldRequest()
						.withDomainName(domainName).withIndexField(field));
			} else {
				// It already exists
				log.info("IndexField: " + field.getIndexFieldName()
						+ " already exists");
				// Is the existing field different than the expected.
				if (!currentField.equals(field)) {
					log.warn(String
							.format("IndexField already exists and does not match the expected value.  Expected: %1$s Actual: %2$s",
									field.toString(), currentField
											.toString()));
					log.info("Updating IndexField: "
							+ field.getIndexFieldName());
					awsSearchClient
							.defineIndexField(new DefineIndexFieldRequest()
									.withDomainName(domainName).withIndexField(
											field));
				}
			}
		}
		// Delete any index field that should not be in the schema
		for(IndexField is: currentFieldsMap.values()){
			// Remove any field that is not used.
			if(!indexList.contains(is)){
				awsSearchClient.deleteIndexField(new DeleteIndexFieldRequest().withDomainName(domainName).withIndexFieldName(is.getIndexFieldName()));
			}
		}
	}

	/**
	 * The domain name of the search index.
	 * 
	 * @return
	 */
	public String getSearchDomainName() {
		return SEARCH_DOMAIN_NAME;
	}

	public DomainStatus getDomainStatus() {
		return getDomainStatus(getSearchDomainName());
	}

	public List<IndexFieldStatus> getIndexFieldStatus() {
		DescribeIndexFieldsResult difr = awsSearchClient
				.describeIndexFields(new DescribeIndexFieldsRequest()
						.withDomainName(getSearchDomainName()));
		return difr.getIndexFields();
	}

	public AccessPoliciesStatus getAccessPoliciesStatus() {
		DescribeServiceAccessPoliciesResult dsapr = awsSearchClient
				.describeServiceAccessPolicies(new DescribeServiceAccessPoliciesRequest()
						.withDomainName(getSearchDomainName()));
		return dsapr.getAccessPolicies();
	}

	/**
	 * Wait for a domain
	 * 
	 * @param domainName
	 * @throws InterruptedException
	 */
	public void waitForDomainProcessing(String domainName)
			throws InterruptedException {
		DomainStatus status = getDomainStatus(domainName);
		if (status == null) {
			// The domain does not exist
			return;
		}
		long start = System.currentTimeMillis();
		while (status.isProcessing()) {
			if (status.isDeleted()) {
				log.warn("Search domain: " + domainName + " has been deleted!");
			}
			long elapse = System.currentTimeMillis() - start;
			log.info(String.format("Waiting for search domain. Elapse time: %1$tM:%1$tS:%1$tL. Current status: %2$s",elapse, status.toString()));
			Thread.sleep(5 * 1000);
			status = getDomainStatus(domainName);
		}
		log.info("Search domain is ready: " + status.toString());
	}

	/**
	 * Fetch the current domain status.
	 * 
	 * @param domainName
	 * @return
	 */
	private DomainStatus getDomainStatus(String domainName) {
		DescribeDomainsResult result = awsSearchClient
				.describeDomains(new DescribeDomainsRequest()
						.withDomainNames(domainName));
		if (result.getDomainStatusList().size() == 0)
			return null;
		if (result.getDomainStatusList().size() != 1)
			throw new IllegalArgumentException("Expected one and only one search domain with the name: "
							+ domainName + " but found: "
							+ result.getDomainStatusList().size());
		return result.getDomainStatusList().get(0);
	}

	@Override
	public String getSearchEndpoint() {
		DomainStatus status = getDomainStatus();
		return  String.format(SEARCH_ENDPOINT_TEMPALTE, status.getSearchService().getEndpoint());
	}

	@Override
	public String getDocumentEndpoint() {
		DomainStatus status = getDomainStatus();
		return String.format(DOCUMENT_ENDPOINT_TEMPALTE, status.getDocService().getEndpoint());
	}
}
