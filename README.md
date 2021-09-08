# custom-provisioning-handler
The requirement is to provision the existing roles in the Key-cloak server to the APIM platform without one-to-one mapping, for the users(API-developers, APP-developers, and admins) who log in through the OIDC SSO flow via Key-cloak to the APIM portals.

To achieve this . (Please refer the JIT Provisioning section). A custom provisioning handler needs to be implemented . This repository has has a simple custom provisioning handler which achieve this.

# Build, Deploy & Run
Build
Clone the repository, change directory into it and execute the following command to build the project

mvn clean install

# Deploy
Copy and place the built JAR artifact from the /target/org.wso2.custom.handlers-1.0.0.jar to the <APIM_HOME>/repository/components/lib directory.(the built JAR artifact can be found in the releases section)

Navigate to <IS_HOME>/repository/conf/deployment.toml and add the following configuration.

[authentication.framework.extensions] 
provisioning_handler="org.wso2.custom.handlers.NewCustomProvisioningHandler"


# Run
Restart the server and test the JIT provisioning flow.

[1]https://github.com/wso2/carbon-identity-framework/blob/196c92bfa25dc4028f0c845046e9dd9869745a1b/components/authentication-framework/org.wso2.carbon.identity.application.authentication.framework/src/main/java/org/wso2/carbon/identity/application/authentication/framework/handler/provisioning/impl/DefaultProvisioningHandler.java#L91
