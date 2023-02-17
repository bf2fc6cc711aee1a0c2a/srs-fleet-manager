package org.bf2.srs.fleetmanager.auth.interceptor;

import io.quarkus.security.identity.SecurityIdentity;
import org.bf2.srs.fleetmanager.auth.AuthService;
import org.bf2.srs.fleetmanager.auth.NotAuthorizedException;
import org.bf2.srs.fleetmanager.common.storage.RegistryNotFoundException;
import org.bf2.srs.fleetmanager.common.storage.ResourceStorage;
import org.bf2.srs.fleetmanager.common.storage.model.RegistryData;
import org.bf2.srs.fleetmanager.operation.auditing.AuditingEvent;
import org.bf2.srs.fleetmanager.operation.auditing.AuditingService;
import org.bf2.srs.fleetmanager.spi.common.model.AccountInfo;
import org.bf2.srs.fleetmanager.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import javax.annotation.Priority;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;

import static org.bf2.srs.fleetmanager.util.SecurityUtil.isResolvable;

@CheckReadPermissions
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class CheckReadPermissionsInterceptor {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Inject
    Instance<SecurityIdentity> securityIdentity;

    @Inject
    AuthService authService;

    @Inject
    ResourceStorage storage;

    @Inject
    AuditingService audit;

    @AroundInvoke
    public Object intercept(InvocationContext context) throws Exception {
        if (isResolvable(securityIdentity)) {
            final AccountInfo accountInfo = authService.extractAccountInfo();
            final Optional<RegistryData> registry = storage.getRegistryById(context.getParameters()[0].toString());
            if (userCanReadInstance(accountInfo, registry)) {
                return context.proceed();
            }
        } else {
            return context.proceed();
        } // TODO Refactor for readability
        log.info("Attempt to read registry instance without the proper permissions");
        var ae = new AuditingEvent();
        ae.setEventId("authorization_failure");
        ae.addData("target", "registry");
        ae.addData("operation", "read");
        audit.recordEvent(ae);
        throw new NotAuthorizedException();
    }

    private static boolean userCanReadInstance(AccountInfo accountInfo, Optional<RegistryData> registry) throws RegistryNotFoundException {
        if (null == accountInfo.getAccountId()) {
            throw new IllegalStateException("Account id cannot be null in the jwt");
        } else if (registry.isPresent()) {
            if (accountInfo.getOrganizationId() != null) {
                if (accountInfo.getOrganizationId().equals(registry.get().getOrgId())) {
                    return true;
                } else {
                    //throw not found exception to avoid leaking information of other registries from users in other organizations
                    throw new RegistryNotFoundException(registry.get().getId());
                }
            } else {
                return SecurityUtil.isInstanceOwner(accountInfo, registry.get().getOwnerId());
            }
        } else {
            return true;
        }
    }

}
