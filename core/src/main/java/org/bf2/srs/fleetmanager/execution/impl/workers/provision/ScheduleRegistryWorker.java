package org.bf2.srs.fleetmanager.execution.impl.workers.provision;

import org.bf2.srs.fleetmanager.execution.impl.tasks.provision.ProvisionRegistryTenantTask;
import org.bf2.srs.fleetmanager.execution.impl.tasks.provision.ScheduleRegistryTask;
import org.bf2.srs.fleetmanager.execution.impl.workers.AbstractWorker;
import org.bf2.srs.fleetmanager.execution.manager.Task;
import org.bf2.srs.fleetmanager.execution.manager.TaskManager;
import org.bf2.srs.fleetmanager.execution.manager.WorkerContext;
import org.bf2.srs.fleetmanager.rest.service.model.RegistryDeploymentStatusValue;
import org.bf2.srs.fleetmanager.rest.service.model.RegistryInstanceTypeValueDto;
import org.bf2.srs.fleetmanager.rest.service.model.RegistryStatusValueDto;
import org.bf2.srs.fleetmanager.common.storage.RegistryNotFoundException;
import org.bf2.srs.fleetmanager.common.storage.ResourceStorage;
import org.bf2.srs.fleetmanager.common.storage.RegistryStorageConflictException;
import org.bf2.srs.fleetmanager.common.storage.model.RegistryData;
import org.bf2.srs.fleetmanager.common.storage.model.RegistryDeploymentData;
import org.bf2.srs.fleetmanager.spi.ams.AccountManagementServiceException;
import org.bf2.srs.fleetmanager.spi.ams.ResourceLimitReachedException;
import org.bf2.srs.fleetmanager.spi.ams.TermsRequiredException;
import org.bf2.srs.fleetmanager.spi.common.EvalInstancesNotAllowedException;
import org.bf2.srs.fleetmanager.spi.common.TooManyEvalInstancesForUserException;
import org.bf2.srs.fleetmanager.spi.common.model.AccountInfo;
import org.bf2.srs.fleetmanager.spi.common.model.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;

import static java.util.stream.Collectors.toList;
import static org.bf2.srs.fleetmanager.execution.impl.tasks.TaskType.SCHEDULE_REGISTRY_T;
import static org.bf2.srs.fleetmanager.execution.impl.workers.WorkerType.SCHEDULE_REGISTRY_W;

/**
 * This class MUST be thread safe. It should not contain state and inject thread safe beans only.
 *
 * @author Jakub Senko <jsenko@redhat.com>
 */
@ApplicationScoped
public class ScheduleRegistryWorker extends AbstractWorker {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Inject
    ResourceStorage storage;

    @Inject
    TaskManager tasks;

    public ScheduleRegistryWorker() {
        super(SCHEDULE_REGISTRY_W);
    }

    @Override
    public boolean supports(Task task) {
        return SCHEDULE_REGISTRY_T.name().equals(task.getType());
    }

    @Override
    @Transactional
    public void execute(Task aTask, WorkerContext ctl) throws RegistryStorageConflictException, EvalInstancesNotAllowedException, AccountManagementServiceException, TooManyEvalInstancesForUserException, TermsRequiredException, ResourceLimitReachedException {
        ScheduleRegistryTask task = (ScheduleRegistryTask) aTask;
        Optional<RegistryData> registryOptional = storage.getRegistryById(task.getRegistryId());

        if (registryOptional.isEmpty()) {
            // NOTE: Failure point 1
            ctl.retry();
        }

        RegistryData registry = registryOptional.get();

        List<RegistryDeploymentData> eligibleRegistryDeployments = storage.getAllRegistryDeployments().stream()
                .filter(rd -> RegistryDeploymentStatusValue.of(rd.getStatus().getValue()) == RegistryDeploymentStatusValue.AVAILABLE)
                .collect(toList());

        if (eligibleRegistryDeployments.isEmpty()) {
            // NOTE: Failure point 2
            // TODO How to report it better?
            log.warn("Could not schedule registry with ID {}. No deployments are available.", registry.getId());
            ctl.retry(100); // We can wait here longer, somebody needs to create a deployment
        }

        // Schedule to a random registry deployment
        // TODO Improve & use a specific scheduling strategy
        RegistryDeploymentData registryDeployment = eligibleRegistryDeployments.get(ThreadLocalRandom.current().nextInt(eligibleRegistryDeployments.size()));

        log.info("Scheduling {} to {}.", registry, registryDeployment); // TODO only available

        registry.setRegistryDeployment(registryDeployment);
        registry.setStatus(RegistryStatusValueDto.PROVISIONING.value());

        // NOTE: Failure point 3
        storage.createOrUpdateRegistry(registry);

        ctl.delay(() -> tasks.submit(ProvisionRegistryTenantTask.builder().registryId(registry.getId()).build()));
    }

    @Override
    public void finallyExecute(Task aTask, WorkerContext ctl, Optional<Exception> error) throws RegistryNotFoundException, RegistryStorageConflictException {
        ScheduleRegistryTask task = (ScheduleRegistryTask) aTask;

        // SUCCESS STATE
        Optional<RegistryData> registryOpt = storage.getRegistryById(task.getRegistryId());
        if (registryOpt.isPresent() && registryOpt.get().getRegistryDeployment() != null && registryOpt.get().getStatus().equals(RegistryStatusValueDto.PROVISIONING.value()))
            return;

        if (registryOpt.isPresent()) {
            log.info("Registry data {} to be deleted", registryOpt);
        }

        // The only thing to handle is if we were able to schedule but storage does not work
        // In that case, the only thing to do is to just try deleting the registry.
        storage.deleteRegistry(task.getRegistryId());
    }
}
