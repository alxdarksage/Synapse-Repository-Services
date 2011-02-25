package org.sagebionetworks.repo.model.gaejdo;

import java.util.ArrayList;
import java.util.Collection;

import javax.jdo.PersistenceManager;

import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.InvalidModelException;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserCredentialsDAO;
import org.sagebionetworks.repo.model.UserCredentials;
import org.sagebionetworks.repo.web.NotFoundException;

import com.google.appengine.api.datastore.KeyFactory;

/**
 * @author deflaux
 * 
 */
public class GAEJDOUserCredentialsDAOImpl extends
		GAEJDOBaseDAOImpl<UserCredentials, GAEJDOUser> implements
		UserCredentialsDAO {

	/**
	 * @param userId
	 */
	public GAEJDOUserCredentialsDAOImpl(String userId) {
		super(userId);
	}
	
	@Override
	public UserCredentials get(String id) throws DatastoreException, NotFoundException,
	UnauthorizedException {
		
		// TODO Bruce, is this how you want this to happen?
		
		PersistenceManager pm = PMF.get();
		GAEJDOUser thisUser = (new GAEJDOUserDAOImpl(userId)).getUser(pm);

		return super.get(KeyFactory.keyToString(thisUser.getId()));
	}
	
	@Override
	public void update(UserCredentials dto) throws DatastoreException, InvalidModelException,
	NotFoundException, UnauthorizedException {
		
		// TODO Bruce, is this how you want this to happen?
		
		PersistenceManager pm = PMF.get();
		GAEJDOUser thisUser = (new GAEJDOUserDAOImpl(userId)).getUser(pm);
		dto.setId(KeyFactory.keyToString(thisUser.getId()));
		super.update(dto);
	}
	
	
	@Override
	protected UserCredentials newDTO() {
		return new UserCredentials();
	}

	@Override
	protected void copyToDto(GAEJDOUser jdo, UserCredentials dto)
			throws DatastoreException {
		dto.setId(KeyFactory.keyToString(jdo.getId()));
		dto.setIamAccessId(jdo.getIamAccessId());
		dto.setIamSecretKey(jdo.getIamSecretKey());
	}

	@Override
	protected void copyFromDto(UserCredentials dto, GAEJDOUser jdo)
			throws InvalidModelException {
		//
		// Confirm that the DTO is valid by checking that all required fields
		// are set
		//
		// Question: is this where we want this sort of logic?
		// Dev Note: right now the only required field is name and type but I
		// can imagine
		// that the
		// validation logic will become more complex over time
		if (null == dto.getIamAccessId()) {
			throw new InvalidModelException(
					"'iamAccessId' is a required property for UserCredentials");
		}
		if (null == dto.getIamSecretKey()) {
			throw new InvalidModelException(
					"'iamSecretKey' is a required property for UserCredentials");
		}
		jdo.setIamAccessId(dto.getIamAccessId());
		jdo.setIamSecretKey(dto.getIamSecretKey());
	}

	@Override
	protected Class<GAEJDOUser> getJdoClass() {
		return GAEJDOUser.class;
	}

	@Override
	public Collection<String> getPrimaryFields() {
		Collection<String> fields = new ArrayList<String>();
		fields.add("preview");
		return fields;
	}

	@Override
	protected GAEJDOUser newJDO() {
		GAEJDOUser jdo = new GAEJDOUser();
		return jdo;
	}

}
