# custom-provisioning-handler
When JIT provisioning users, Identity Server will generate a random password when adding a user to the user store as it is mandatory to have a password in the user store manager level. According to the logic[1], the generated password is a random string whose length is 12. So if you have a password policy or regex rejecting the internally generated password it will cause an error when the user is added.

The steps to resolve this issue is documentated[2]. (Please refer the JIT Provisioning section). A custom provisioning handler needs to be implemented . This repository has has a simple custom provisioning handler which generates a random password.

# Build, Deploy & Run
Build
Clone the repository, change directory into it and execute the following command to build the project

mvn clean install

# Deploy
Copy and place the built JAR artifact from the /target/org.wso2.custom.handlers-1.0.0.jar to the <APIM_HOME>/repository/components/lib directory.(the built JAR artifact can be found in the releases section)

Navigate to <IS_HOME>/repository/conf/deployment.toml and add the following configuration.

[authentication.framework.extensions] 
provisioning_handler="org.wso2.custom.handlers.CustomProvisioningHandler"


# Run
Restart the server and test the JIT provisioning flow.

[1]https://github.com/wso2/carbon-identity-framework/blob/master/components/authentication-framework/org.wso2.carbon.identity.application.authentication.framework/src/main/java/org/wso2/carbon/identity/application/authentication/framework/handler/provisioning/impl/DefaultProvisioningHandler.java#L416 [2]https://is.docs.wso2.com/en/5.10.0/learn/password-patterns/
