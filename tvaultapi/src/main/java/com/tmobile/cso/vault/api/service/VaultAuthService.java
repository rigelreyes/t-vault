// =========================================================================
// Copyright 2019 T-Mobile, US
// 
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// See the readme.txt file for additional language around disclaimer of warranties.
// =========================================================================

package com.tmobile.cso.vault.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.tmobile.cso.vault.api.authentication.VaultAuthFactory;
import com.tmobile.cso.vault.api.common.TVaultConstants;
import com.tmobile.cso.vault.api.exception.LogMessage;
import com.tmobile.cso.vault.api.utils.ThreadLocalContext;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import com.tmobile.cso.vault.api.model.UserLogin;
import com.tmobile.cso.vault.api.process.RequestProcessor;
import com.tmobile.cso.vault.api.process.Response;
import com.tmobile.cso.vault.api.utils.AuthorizationUtils;
import com.tmobile.cso.vault.api.utils.JSONUtil;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class  VaultAuthService {

	@Autowired
	private RequestProcessor reqProcessor;
	
	@Autowired
	private AuthorizationUtils authorizationUtils;

	@Autowired
	private VaultAuthFactory vaultAuthFactory;

	@Value("${vault.auth.method}")
	private String vaultAuthMethod;

	@Value("${selfservice.enable}")
	private boolean isSSEnabled;

	@Value("${ad.passwordrotation.enable}")
	private boolean isAdPswdRotationEnabled;

	private static Logger log = LogManager.getLogger(VaultAuthService.class);

	/**
	 * Logs a user in to TVault using ldap or userpass authentication methods
	 * @param jsonStr
	 * @return
	 */
	private ResponseEntity<String> login(String jsonStr) {
		Response response = vaultAuthFactory.login(jsonStr);

		if(HttpStatus.OK.equals(response.getHttpstatus())){
			Map<String, Object> responseMap = null;
			try {
				responseMap = new ObjectMapper().readValue(response.getResponse(), new TypeReference<Map<String, Object>>(){});
			} catch (IOException e) {
				log.error(JSONUtil.getJSON(ImmutableMap.<String, String>builder().
						put(LogMessage.USER, ThreadLocalContext.getCurrentMap().get(LogMessage.USER).toString()).
						put(LogMessage.ACTION, "Login").
						put(LogMessage.MESSAGE, String.format("Login check for duplicate safe permission failed")).
						put(LogMessage.RESPONSE, "Invalid login response").
						put(LogMessage.APIURL, ThreadLocalContext.getCurrentMap().get(LogMessage.APIURL).toString()).
						build()));
			}
			if(responseMap!=null && responseMap.get("access")!=null) {
				Map<String,Object> access = (Map<String,Object>)responseMap.get("access");
                // checking for write-only permission
                List<String> policies = (List<String>)responseMap.get("policies");
                if (!CollectionUtils.isEmpty(policies)) {
                    List<String> writeOnlyPolicies = policies.stream().filter(p->p.startsWith(TVaultConstants.WRITE_ONLY_PREFIX)).collect(Collectors.toList());
                    if (!CollectionUtils.isEmpty(writeOnlyPolicies)) {
                        for (String policy: writeOnlyPolicies) {
                            String policyInfo[] = policy.split("_");
                            if (policyInfo.length >=3) {
                                String safeType = policyInfo[1];
                                String safeNameArr[] = Arrays.copyOfRange(policyInfo, 2, policyInfo.length);
                                Map<String, String> newAccess = new HashMap<>();
                                newAccess.put(StringUtils.join(safeNameArr, "_"), TVaultConstants.WRITEONLY_POLICY);

                                List<Map<String,String>> safePermissions = (List<Map<String,String>>)access.get(safeType);
                                if (safePermissions ==null || safePermissions.isEmpty()) {
                                    safePermissions = new ArrayList<>();
                                }
                                safePermissions.add(newAccess);
                                access.put(safeType, safePermissions);
                            }
                        }
                    }
                }

				access = filterDuplicateSafePermissions(access);
				access = filterDuplicateSvcaccPermissions(access);
				responseMap.put("access", access);
				// set SS, AD password rotation enable status
				Map<String,Object> feature = new HashMap<>();
				feature.put(TVaultConstants.SELFSERVICE, isSSEnabled);
				feature.put(TVaultConstants.ADAUTOROTATION, isAdPswdRotationEnabled);
				responseMap.put("feature", feature);


				response.setResponse(JSONUtil.getJSON(responseMap));
			}

			return ResponseEntity.status(response.getHttpstatus()).body(response.getResponse());
		}else{
			if (HttpStatus.BAD_REQUEST.equals(response.getHttpstatus())) {
				return ResponseEntity.status(response.getHttpstatus()).body("{\"errors\": [\"User Authentication failed\", \"Invalid username or password. Please retry again after correcting username or password.\"]}");
			}
			else if (HttpStatus.INTERNAL_SERVER_ERROR.equals(response.getHttpstatus())) {
				return ResponseEntity.status(response.getHttpstatus()).body("{\"errors\": [\"User Authentication failed\", \"This may be due to vault services are down or vault services are not reachable\"]}");
			}
			else if (HttpStatus.UNPROCESSABLE_ENTITY.equals(response.getHttpstatus())) {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response.getResponse());
			}
			return ResponseEntity.status(response.getHttpstatus()).body("{\"errors\":[\"Username Authentication Failed.\"]}");
		}
	}
	/**
	 * To filter the duplicate safe permissions
	 * @param access
	 * @return
	 */
	private Map<String,Object> filterDuplicateSafePermissions(Map<String,Object> access) {
		if (!MapUtils.isEmpty(access)) {
			String[] safeTypes = {TVaultConstants.USERS, TVaultConstants.SHARED, TVaultConstants.APPS};

			for (String type: safeTypes) {
				List<Map<String,String>> safePermissions = (List<Map<String,String>>)access.get(type);
				if (safePermissions != null) {
					//map to check duplicate permission
					Map<String,String> filteredPermissions = Collections.synchronizedMap(new HashMap());
					List<Map<String,String>> updatedPermissionList = new ArrayList<>();
					for (Map<String,String> permissionMap: safePermissions) {
						Set<String> keys = permissionMap.keySet();
						String key = keys.stream().findFirst().orElse("");

						if (key !="" && !filteredPermissions.containsKey(key)) {
							filteredPermissions.put(key, permissionMap.get(key));
							Map<String,String> permission = Collections.synchronizedMap(new HashMap());
							permission.put(key, permissionMap.get(key));
							updatedPermissionList.add(permission);
						}
					}
					access.put(type, updatedPermissionList);
				}
			}
		}
		return access;
	}

	/**
	 * To filter the duplicate Service account permissions
	 * @param access
	 * @return
	 */
	private Map<String,Object> filterDuplicateSvcaccPermissions(Map<String,Object> access) {
		if (!MapUtils.isEmpty(access)) {
			List<Map<String,String>> svcaccPermissions = (List<Map<String,String>>)access.get(TVaultConstants.SVC_ACC_PATH_PREFIX);
			if (svcaccPermissions != null) {
				//map to check duplicate permission
				Map<String,String> filteredPermissions = Collections.synchronizedMap(new HashMap());
				List<Map<String,String>> updatedPermissionList = new ArrayList<>();
				for (Map<String,String> permissionMap: svcaccPermissions) {
					Set<String> keys = permissionMap.keySet();
					String key = keys.stream().findFirst().orElse("");

					if (key !="" && !filteredPermissions.containsKey(key)) {
						filteredPermissions.put(key, permissionMap.get(key));
						Map<String,String> permission = Collections.synchronizedMap(new HashMap());
						permission.put(key, permissionMap.get(key));
						updatedPermissionList.add(permission);
					}
				}
				access.put(TVaultConstants.SVC_ACC_PATH_PREFIX, updatedPermissionList);
			}
		}
		return access;
	}

	/**
	 * Logs a user in to TVault using ldap or userpass authentication methods
	 * @param user
	 * @return
	 */
	public ResponseEntity<String> login(UserLogin user) {
		String jsonStr = JSONUtil.getJSON(user);
		return login(jsonStr);
	}
	/**
	 * Renews vault token for a given user token
	 * @param jsonStr
	 * @return
	 */
	public ResponseEntity<String> renew(String token) {
		Response response = reqProcessor.process("/auth/tvault/renew","{}", token);	
 		if(HttpStatus.OK.equals(response.getHttpstatus())){
			return ResponseEntity.status(response.getHttpstatus()).body(response.getResponse());
		}else{
			return ResponseEntity.status(response.getHttpstatus()).body("{\"errors\":[\"Self renewal of token Failed.\"]}");
		}
	}
	
	/**
	 * Looks up the Login details from Vault for a given user token
	 * @param token
	 * @return
	 */
	public ResponseEntity<String> lookup( String token){
		Response response = reqProcessor.process("/auth/tvault/lookup","{}", token);	
 		if(HttpStatus.OK.equals(response.getHttpstatus())){
			return ResponseEntity.status(response.getHttpstatus()).body(response.getResponse());
		}else{
			return ResponseEntity.status(response.getHttpstatus()).body("{\"errors\":[\"Token Lookup Failed.\"]}");
		}
	}
	/**
	 * Logs the user out from Vault based on given user token
	 * @param token
	 * @return
	 */
	public ResponseEntity<String> revoke(String token){
		Response response = reqProcessor.process("/auth/tvault/revoke","{}", token);	
 		if(HttpStatus.OK.equals(response.getHttpstatus())){
			return ResponseEntity.status(response.getHttpstatus()).body(response.getResponse());
		}else{
			return ResponseEntity.status(response.getHttpstatus()).body("{\"errors\":[\"Token revoke Failed.\"]}");
		}
	}

}
