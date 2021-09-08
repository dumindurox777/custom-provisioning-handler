package com.wso2.custom.handler;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.core.util.PermissionUpdateUtil;
import org.wso2.carbon.identity.application.authentication.framework.exception.FrameworkException;
import org.wso2.carbon.identity.application.authentication.framework.handler.provisioning.impl.DefaultProvisioningHandler;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.identity.application.common.util.IdentityApplicationConstants;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.user.profile.mgt.association.federation.exception.FederatedAssociationManagerException;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.UserRealm;
import org.wso2.carbon.user.core.UserStoreException;
import org.wso2.carbon.user.core.UserStoreManager;
import org.wso2.carbon.user.core.claim.Claim;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import java.util.*;
import java.util.stream.Collectors;

import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.Config.SEND_MANUALLY_ADDED_LOCAL_ROLES_OF_IDP;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.Config.SEND_ONLY_LOCALLY_MAPPED_ROLES_OF_IDP;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.InternalRoleDomains.APPLICATION_DOMAIN;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.InternalRoleDomains.WORKFLOW_DOMAIN;
import static org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants.USERNAME_CLAIM;


public class NewCustomProvisioningHandler extends DefaultProvisioningHandler {

    private static final Log log = LogFactory.getLog(NewCustomProvisioningHandler.class);
    private static final String ALREADY_ASSOCIATED_MESSAGE = "UserAlreadyAssociated";
    private static final String USER_WORKFLOW_ENGAGED_ERROR_CODE = "WFM-10001";


    @Override
    public void handle(List<String> roles, String subject, Map<String, String> attributes,
                       String provisioningUserStoreId, String tenantDomain) throws FrameworkException {

        List<String> idpToLocalRoleMapping =
                (List<String>) IdentityUtil.threadLocalProperties.get().get(FrameworkConstants.IDP_TO_LOCAL_ROLE_MAPPING);
        handle(roles, subject, attributes, provisioningUserStoreId, tenantDomain, idpToLocalRoleMapping);

    }

    @Override
    public void handle(List<String> roles, String subject, Map<String, String> attributes,
                       String provisioningUserStoreId, String tenantDomain, List<String> idpToLocalRoleMapping)
            throws FrameworkException {
        RealmService realmService = null;
        try {

            realmService = getRealmService();
            int tenantId = realmService.getTenantManager().getTenantId(tenantDomain);
            realmService.getTenantUserRealm(tenantId);
            UserRealm realm = (UserRealm) realmService.getTenantUserRealm(tenantId);
            String username = MultitenantUtils.getTenantAwareUsername(subject);

            String userStoreDomain;
            UserStoreManager userStoreManager;
            if (IdentityApplicationConstants.AS_IN_USERNAME_USERSTORE_FOR_JIT
                    .equalsIgnoreCase(provisioningUserStoreId)) {
                String userStoreDomainFromSubject = UserCoreUtil.extractDomainFromName(subject);
                try {
                    userStoreManager = getUserStoreManager(realm, userStoreDomainFromSubject);
                    userStoreDomain = userStoreDomainFromSubject;
                } catch (FrameworkException e) {
                    log.error("User store domain " + userStoreDomainFromSubject + " does not exist for the tenant "
                            + tenantDomain + ", hence provisioning user to "
                            + UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME);
                    userStoreDomain = UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME;
                    userStoreManager = getUserStoreManager(realm, userStoreDomain);
                }
            } else {
                userStoreDomain = getUserStoreDomain(provisioningUserStoreId, realm);
                userStoreManager = getUserStoreManager(realm, userStoreDomain);
            }
            username = UserCoreUtil.removeDomainFromName(username);

            if (log.isDebugEnabled()) {
                log.debug("User: " + username + " with roles : " + roles + " is going to be provisioned");
            }

            // If internal roles exists convert internal role domain names to pre defined camel case domain names.
            List<String> rolesToAdd = convertInternalRoleDomainsToCamelCase(roles);

            String idp = attributes.remove(FrameworkConstants.IDP_ID);
            String subjectVal = attributes.remove(FrameworkConstants.ASSOCIATED_ID);

            Map<String, String> userClaims = prepareClaimMappings(attributes);

            if (userStoreManager.isExistingUser(username)) {
                if (!userClaims.isEmpty()) {
                    userClaims.remove(FrameworkConstants.PASSWORD);
                    userClaims.remove(USERNAME_CLAIM);
                    userStoreManager.setUserClaimValues(UserCoreUtil.removeDomainFromName(username), userClaims, null);
                    /*
                    Since the user is exist following code is get all active claims of user and crosschecking against
                    tobeDeleted claims (claims came from federated idp as null). If there is a match those claims
                    will be deleted.
                    */
                    List<String> toBeDeletedUserClaims = prepareToBeDeletedClaimMappings(attributes);
                    if (CollectionUtils.isNotEmpty(toBeDeletedUserClaims)) {
                        Claim[] userActiveClaims =
                                userStoreManager.getUserClaimValues(UserCoreUtil.removeDomainFromName(username), null);
                        for (Claim claim : userActiveClaims) {
                            if (toBeDeletedUserClaims.contains(claim.getClaimUri())) {
                                if (log.isDebugEnabled()) {
                                    log.debug("Claim from external attributes " + claim.getClaimUri() + " has null " +
                                            "value. But user has not null claim value for Claim " +
                                            claim.getClaimUri() +
                                            ". Hence user claim value will be deleted.");
                                }
                                userStoreManager.deleteUserClaimValue(UserCoreUtil.removeDomainFromName(username),
                                        claim.getClaimUri(), null);
                            }
                        }
                    }
                }
                String associatedUserName = FrameworkUtils.getFederatedAssociationManager()
                        .getUserForFederatedAssociation(tenantDomain, idp, subjectVal);
                if (StringUtils.isEmpty(associatedUserName)) {
                    // Associate User
                    associateUser(username, userStoreDomain, tenantDomain, subjectVal, idp);
                }
            } else {
                String password = generatePassword();
                String passwordFromUser = userClaims.get(FrameworkConstants.PASSWORD);
                if (StringUtils.isNotEmpty(passwordFromUser)) {
                    password = passwordFromUser;
                }

                // Check for inconsistencies in username attribute and the username claim.
                if (userClaims.containsKey(USERNAME_CLAIM) && !userClaims.get(USERNAME_CLAIM).equals(username)) {
                    // If so update the username claim with the username attribute.
                    userClaims.put(USERNAME_CLAIM, username);
                }

                userClaims.remove(FrameworkConstants.PASSWORD);
                boolean userWorkflowEngaged = false;
                try {
                    userStoreManager.addUser(username, password, null, userClaims, null);
                } catch (UserStoreException e) {
                    // Add user operation will fail if a user operation workflow is already defined for the same user.
                    if (USER_WORKFLOW_ENGAGED_ERROR_CODE.equals(e.getErrorCode())) {
                        userWorkflowEngaged = true;
                        if (log.isDebugEnabled()) {
                            log.debug("Failed to add the user while JIT provisioning since user workflows are engaged" +
                                    " and there is a workflow already defined for the same user");
                        }
                    } else {
                        throw e;
                    }
                }
                if (userWorkflowEngaged ||
                        !userStoreManager.isExistingUser(UserCoreUtil.addDomainToName(username, userStoreDomain))) {
                    if (log.isDebugEnabled()) {
                        log.debug("User is not found in the userstore. Most probably the local user creation is not " +
                                "complete while JIT provisioning due to user operation workflow engagement. Therefore" +
                                " the user account association and role and permission update are skipped.");
                    }
                    return;
                }

                // Associate user only if the user is existing in the userstore.
                associateUser(username, userStoreDomain, tenantDomain, subjectVal, idp);

                if (log.isDebugEnabled()) {
                    log.debug("Federated user: " + username + " is provisioned by authentication framework.");
                }
            }

            boolean includeManuallyAddedLocalRoles = Boolean
                    .parseBoolean(IdentityUtil.getProperty(SEND_MANUALLY_ADDED_LOCAL_ROLES_OF_IDP));

            List<String> currentRolesList = Arrays.asList(userStoreManager.getRoleListOfUser(username));
            Collection<String> deletingRoles = retrieveRolesToBeDeleted(realm, currentRolesList, rolesToAdd);

            // Updating user roles.
            if (roles != null && roles.size() > 0) {

                if (idpToLocalRoleMapping != null && !idpToLocalRoleMapping.isEmpty()) {
                    boolean excludeUnmappedRoles = true;

                    if (StringUtils.isNotEmpty(IdentityUtil.getProperty(SEND_ONLY_LOCALLY_MAPPED_ROLES_OF_IDP))) {
                        excludeUnmappedRoles = Boolean
                                .parseBoolean(IdentityUtil.getProperty(SEND_ONLY_LOCALLY_MAPPED_ROLES_OF_IDP));
                    }

                    if (excludeUnmappedRoles && includeManuallyAddedLocalRoles) {
                        /*
                            Get the intersection of deletingRoles with idpRoleMappings. Here we're deleting mapped
                            roles and keeping manually added local roles.
                        */
                        deletingRoles = deletingRoles.stream().distinct().filter(idpToLocalRoleMapping::contains)
                                .collect(Collectors.toSet());
                    }
                }

                // No need to add already existing roles again.
                rolesToAdd.removeAll(currentRolesList);

                // add roles that doesn't exists in the system which are not having any mapping.
                for (String role : rolesToAdd) {
                    if (!userStoreManager.isExistingRole(role)) {
                        userStoreManager.addRole(role,null,null);
                    }
                }

                // TODO : Does it need to check this?
                // Check for case whether super admin login
                handleFederatedUserNameEqualsToSuperAdminUserName(realm, username, userStoreManager, deletingRoles);

                updateUserWithNewRoleSet(username, userStoreManager, rolesToAdd, deletingRoles);
            } else {
                if (includeManuallyAddedLocalRoles) {
                    // Remove only IDP mapped roles and keep manually added local roles.
                    if (CollectionUtils.isNotEmpty(idpToLocalRoleMapping)) {
                        deletingRoles = deletingRoles.stream().distinct().filter(idpToLocalRoleMapping::contains)
                                .collect(Collectors.toSet());
                        updateUserWithNewRoleSet(username, userStoreManager, new ArrayList<>(), deletingRoles);
                    }
                } else {
                    // Remove all roles of the user.
                    updateUserWithNewRoleSet(username, userStoreManager, new ArrayList<>(), deletingRoles);
                }
            }

            PermissionUpdateUtil.updatePermissionTree(tenantId);

        } catch (org.wso2.carbon.user.api.UserStoreException |
                FederatedAssociationManagerException e) {
            throw new FrameworkException("Error while provisioning user : " + subject, e);
        } finally {
            IdentityUtil.clearIdentityErrorMsg();
        }

    }

    private UserStoreManager getUserStoreManager(UserRealm realm, String userStoreDomain)
            throws UserStoreException, FrameworkException {
        UserStoreManager userStoreManager;
        if (userStoreDomain != null && !userStoreDomain.isEmpty()) {
            userStoreManager = realm.getUserStoreManager().getSecondaryUserStoreManager(
                    userStoreDomain);
        } else {
            userStoreManager = realm.getUserStoreManager();
        }

        if (userStoreManager == null) {
            throw new FrameworkException("Specified user store is invalid");
        }
        return userStoreManager;
    }

    private void updateUserWithNewRoleSet(String username, UserStoreManager userStoreManager, List<String> rolesToAdd,
                                          Collection<String> deletingRoles) throws UserStoreException {

        if (log.isDebugEnabled()) {
            log.debug("Deleting roles : " + Arrays.toString(deletingRoles.toArray(new String[0]))
                    + " and Adding roles : " + Arrays.toString(rolesToAdd.toArray(new String[0])));
        }
        userStoreManager.updateRoleListOfUser(username, deletingRoles.toArray(new String[0]),
                rolesToAdd.toArray(new String[0]));
        if (log.isDebugEnabled()) {
            log.debug("Federated user: " + username + " is updated by authentication framework with roles : "
                    + rolesToAdd);
        }
    }

    private void handleFederatedUserNameEqualsToSuperAdminUserName(UserRealm realm, String username,
                                                                   UserStoreManager userStoreManager,
                                                                   Collection<String> deletingRoles)
            throws UserStoreException, FrameworkException {
        if (userStoreManager.getRealmConfiguration().isPrimary()
                && username.equals(realm.getRealmConfiguration().getAdminUserName())) {
            if (log.isDebugEnabled()) {
                log.debug("Federated user's username is equal to super admin's username of local IdP.");
            }

            // Whether superadmin login without superadmin role is permitted
            if (deletingRoles
                    .contains(realm.getRealmConfiguration().getAdminRoleName())) {
                if (log.isDebugEnabled()) {
                    log.debug("Federated user doesn't have super admin role. Unable to sync roles, since" +
                            " super admin role cannot be unassigned from super admin user");
                }
                throw new FrameworkException(
                        "Federated user which having same username to super admin username of local IdP," +
                                " trying login without having super admin role assigned");
            }
        }
    }

    private String getUserStoreDomain(String userStoreDomain, UserRealm realm)
            throws FrameworkException, UserStoreException {

        // If the any of above value is invalid, keep it empty to use primary userstore
        if (userStoreDomain != null
                && realm.getUserStoreManager().getSecondaryUserStoreManager(userStoreDomain) == null) {
            throw new FrameworkException("Specified user store domain " + userStoreDomain
                    + " is not valid.");
        }

        return userStoreDomain;
    }

    private List<String> convertInternalRoleDomainsToCamelCase(List<String> roles) {

        List<String> updatedRoles = new ArrayList<>();

        if (roles != null) {
            // If internal roles exist, convert internal role domain names to case sensitive predefined domain names.
            for (String role : roles) {
                if (StringUtils.containsIgnoreCase(role, UserCoreConstants.INTERNAL_DOMAIN + CarbonConstants
                        .DOMAIN_SEPARATOR)) {
                    updatedRoles.add(UserCoreConstants.INTERNAL_DOMAIN + CarbonConstants.DOMAIN_SEPARATOR +
                            UserCoreUtil.removeDomainFromName(role));
                } else if (StringUtils.containsIgnoreCase(role, APPLICATION_DOMAIN + CarbonConstants.DOMAIN_SEPARATOR)) {
                    updatedRoles.add(APPLICATION_DOMAIN + CarbonConstants.DOMAIN_SEPARATOR + UserCoreUtil
                            .removeDomainFromName(role));
                } else if (StringUtils.containsIgnoreCase(role, WORKFLOW_DOMAIN + CarbonConstants.DOMAIN_SEPARATOR)) {
                    updatedRoles.add(WORKFLOW_DOMAIN + CarbonConstants.DOMAIN_SEPARATOR + UserCoreUtil
                            .removeDomainFromName(role));
                } else {
                    updatedRoles.add(role);
                }
            }
        }

        return updatedRoles;
    }
    private Map<String, String> prepareClaimMappings(Map<String, String> attributes) {
        Map<String, String> userClaims = new HashMap<>();
        if (attributes != null && !attributes.isEmpty()) {
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                String claimURI = entry.getKey();
                String claimValue = entry.getValue();
                if (!(StringUtils.isEmpty(claimURI) || StringUtils.isEmpty(claimValue))) {
                    userClaims.put(claimURI, claimValue);
                }
            }
        }
        return userClaims;
    }

    private List<String> prepareToBeDeletedClaimMappings(Map<String, String> attributes) {

        List<String> toBeDeletedUserClaims = new ArrayList<>();
        if (MapUtils.isNotEmpty(attributes)) {
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                String claimURI = entry.getKey();
                String claimValue = entry.getValue();
                if (StringUtils.isNotBlank(claimURI) && StringUtils.isBlank(claimValue)) {
                    toBeDeletedUserClaims.add(claimURI);
                }
            }
        }
        return toBeDeletedUserClaims;
    }

    private static RealmService getRealmService() {

        return (RealmService) PrivilegedCarbonContext.getThreadLocalCarbonContext()
                .getOSGiService(RealmService.class, null);
    }
}