/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License") +  you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.openmeetings.service.user;

import static org.apache.openmeetings.db.dao.user.UserDao.getNewUserInstance;
import static org.apache.openmeetings.db.util.TimezoneUtil.getTimeZone;
import static org.apache.openmeetings.util.OmException.UNKNOWN;
import static org.apache.openmeetings.util.OpenmeetingsVariables.CONFIG_EMAIL_VERIFICATION;
import static org.apache.openmeetings.util.OpenmeetingsVariables.CONFIG_REGISTER_SOAP;
import static org.apache.openmeetings.util.OpenmeetingsVariables.getBaseUrl;
import static org.apache.openmeetings.util.OpenmeetingsVariables.getDefaultGroup;
import static org.apache.openmeetings.util.OpenmeetingsVariables.getDefaultLang;
import static org.apache.openmeetings.util.OpenmeetingsVariables.getMinLoginLength;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

import org.apache.openmeetings.db.dao.basic.ConfigurationDao;
import org.apache.openmeetings.db.dao.label.LabelDao;
import org.apache.openmeetings.db.dao.user.GroupDao;
import org.apache.openmeetings.db.dao.user.IUserManager;
import org.apache.openmeetings.db.dao.user.UserDao;
import org.apache.openmeetings.db.dto.user.OAuthUser;
import org.apache.openmeetings.db.entity.user.GroupUser;
import org.apache.openmeetings.db.entity.user.User;
import org.apache.openmeetings.db.entity.user.User.Right;
import org.apache.openmeetings.db.entity.user.User.Type;
import org.apache.openmeetings.service.mail.EmailManager;
import org.apache.openmeetings.util.OmException;
import org.apache.openmeetings.util.crypt.CryptProvider;
import org.apache.openmeetings.util.crypt.ICrypt;
import org.apache.wicket.util.string.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 *
 * @author swagner
 *
 */
@Component
public class UserManager implements IUserManager {
	private static final Logger log = LoggerFactory.getLogger(UserManager.class);

	@Autowired
	private ConfigurationDao cfgDao;
	@Autowired
	private GroupDao groupDao;
	@Autowired
	private UserDao userDao;
	@Autowired
	private EmailManager emailManager;

	private boolean sendConfirmation() {
		String baseURL = getBaseUrl();
		return baseURL != null
				&& !baseURL.isEmpty()
				&& cfgDao.getBool(CONFIG_EMAIL_VERIFICATION, false);
	}

	/**
	 * Method to register a new User, User will automatically be added to the
	 * default user_level(1) new users will be automatically added to the
	 * Group with the id specified in the configuration value default.group.id
	 *
	 * @param login - user login
	 * @param password - user password
	 * @param lastname - user last name
	 * @param firstname - user first name
	 * @param email - user email
	 * @param country - user country code
	 * @param languageId - language id
	 * @param tzId - the name of the time zone
	 * @return {@link User} of code of error as {@link String}
	 */
	@Override
	public Object registerUser(String login, String password, String lastname,
			String firstname, String email, String country, long languageId, String tzId) {
		try {
			// Checks if FrontEndUsers can register
			if (cfgDao.getBool(CONFIG_REGISTER_SOAP, false)) {
				User u = getNewUserInstance(null);
				u.setFirstname(firstname);
				u.setLogin(login);
				u.setLastname(lastname);
				u.getAddress().setCountry(country);
				u.getAddress().setEmail(email);
				u.setTimeZoneId(getTimeZone(tzId).getID());

				// this is needed cause the language is not a necessary data at registering
				u.setLanguageId(languageId != 0 ? languageId : 1);
				u.getGroupUsers().add(new GroupUser(groupDao.get(getDefaultGroup()), u));

				Object user = registerUser(u, password, null);

				if (user instanceof User && sendConfirmation()) {
					return -40L;
				}

				return user;
			} else {
				return "error.reg.disabled";
			}
		} catch (Exception e) {
			log.error("[registerUser]", e);
		}
		return null;
	}

	/**
	 * @param u - User with basic parametrs set
	 * @param password - user password
	 * @param hash - activation hash
	 * @return {@link User} of code of error as {@link String}
	 * @throws NoSuchAlgorithmException in case password hashing algorithm is not found
	 * @throws OmException in case of any issues with provided data
	 */
	@Override
	public Object registerUser(User u, String password, String hash) throws OmException, NoSuchAlgorithmException {
		// Check for required data
		String login = u.getLogin();
		if (!Strings.isEmpty(login) && login.length() >= getMinLoginLength()) {
			// Check for duplicates
			boolean checkName = userDao.checkLogin(login, User.Type.user, null, null);
			String email = u.getAddress() == null ? null : u.getAddress().getEmail();
			boolean checkEmail = Strings.isEmpty(email) || userDao.checkEmail(email, User.Type.user, null, null);
			if (checkName && checkEmail) {
				String ahash = Strings.isEmpty(hash) ? UUID.randomUUID().toString() : hash;
				if (Strings.isEmpty(u.getExternalType())) {
					if (!Strings.isEmpty(email)) {
						emailManager.sendMail(login, email, ahash, sendConfirmation(), u.getLanguageId());
					}
				} else {
					u.setType(Type.external);
				}

				// If this user needs first to click his E-Mail verification
				// code then set the status to 0
				if (sendConfirmation() && u.getRights().contains(Right.Login)) {
					u.getRights().remove(Right.Login);
				}

				u.setActivatehash(ahash);
				if (!Strings.isEmpty(password)) {
					u.updatePassword(password);
				}
				u = userDao.update(u, null);
				log.debug("Added user-Id {}", u.getId());

				if (u.getId() != null) {
					return u;
				}
			} else {
				if (!checkName) {
					return "error.login.inuse";
				} else {
					return "error.email.inuse";
				}
			}
		} else {
			return "error.short.login";
		}
		return UNKNOWN.getKey();
	}

	/**
	 * @param roomId - id of the room user should be kicked from
	 * @return <code>true</code> if there were no errors
	 */
	@Override
	public boolean kickUsersByRoomId(Long roomId) {
		/*
		try {
			sessionDao.clearSessionByRoomId(roomId);

			for (StreamClient rcl : streamClientManager.list(roomId)) {
				if (rcl == null) {
					return true;
				}
				String scopeName = rcl.getRoomId() == null ? HIBERNATE : rcl.getRoomId().toString();
				IScope currentScope = scopeAdapter.getChildScope(scopeName);
				scopeAdapter.roomLeaveByScope(rcl, currentScope);

				Map<Integer, String> messageObj = new HashMap<>();
				messageObj.put(0, "kick");
				scopeAdapter.sendMessageById(messageObj, rcl.getUid(), currentScope);
			}
			return true;
		} catch (Exception err) {
			log.error("[kickUsersByRoomId]", err);
		}
		*/
		return false;
	}

	@Override
	public boolean kickById(String uid) {
		/*
		try {
			StreamClient rcl = streamClientManager.get(uid);

			if (rcl == null) {
				return true;
			}

			String scopeName = rcl.getScope() == null ? HIBERNATE : rcl.getScope();
			IScope scope = scopeAdapter.getChildScope(scopeName);
			if (scope == null) {
				log.warn("### kickById ### The scope is NULL");
				return false;
			}

			Map<Integer, String> messageObj = new HashMap<>();
			messageObj.put(0, "kick");
			scopeAdapter.sendMessageById(messageObj, uid, scope);

			scopeAdapter.roomLeaveByScope(rcl, scope);

			return true;
		} catch (Exception err) {
			log.error("[kickById]", err);
		}
		*/
		return false;
	}

	@Override
	public Long getLanguage(Locale loc) {
		return LabelDao.getLanguage(loc, getDefaultLang());
	}

	@Override
	public User loginOAuth(OAuthUser user, long serverId) throws IOException, NoSuchAlgorithmException {
		if (!userDao.validLogin(user.getUid())) {
			log.error("Invalid login, please check parameters");
			return null;
		}
		User u = userDao.getByLogin(user.getUid(), Type.oauth, serverId);
		if (!userDao.checkEmail(user.getEmail(), Type.oauth, serverId, u == null ? null : u.getId())) {
			log.error("Another user with the same email exists");
			return null;
		}
		// generate random password
		// check if the user already exists and register new one if it's needed
		if (u == null) {
			u = getNewUserInstance(null);
			u.setType(Type.oauth);
			u.getRights().remove(Right.Login);
			u.setDomainId(serverId);
			u.getGroupUsers().add(new GroupUser(groupDao.get(getDefaultGroup()), u));
			u.setLogin(user.getUid());
			u.setShowContactDataToContacts(true);
			u.setLastname(user.getLastName());
			u.setFirstname(user.getFirstName());
			u.getAddress().setEmail(user.getEmail());
			String picture = user.getPicture();
			if (picture != null) {
				u.setPictureuri(picture);
			}
			String locale = user.getLocale();
			if (locale != null) {
				Locale loc = Locale.forLanguageTag(locale);
				if (loc != null) {
					u.setLanguageId(getLanguage(loc));
					u.getAddress().setCountry(loc.getCountry());
				}
			}
		}
		u.setLastlogin(new Date());
		ICrypt crypt = CryptProvider.get();
		u = userDao.update(u, crypt.randomPassword(25), Long.valueOf(-1));

		return u;
	}
}
