package com.netgrif.mksr.startup

import com.netgrif.application.engine.petrinet.service.interfaces.IPetriNetService
import com.netgrif.application.engine.startup.AbstractOrderedCommandLineRunner
import com.netgrif.application.engine.workflow.domain.Case
import com.netgrif.application.engine.workflow.service.interfaces.ITaskService
import com.netgrif.mksr.migration.MigrationHelper
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

import java.util.stream.Stream

@Slf4j
@Component
@Profile("dev")
class DevelopmentMigration extends AbstractOrderedCommandLineRunner {


    @Autowired
    private IPetriNetService petriNetService

    @Autowired
    private ITaskService taskService

    @Autowired
    MigrationHelper migrationHelper

    @Autowired
    private Environment env

    @Value('${spring.data.mongodb.drop}')
    private boolean mongoDrop

    @Override
    void run(String... args) throws Exception {
        if (!Stream.of(env.getActiveProfiles()).anyMatch({ it -> (it == "dev") }) || mongoDrop) {
            return
        }
        log.info("Migrating nets")
        [
                NetRunner.PetriNetEnum.SUBJECT,
        ].each {
            try {
                log.info("migrating $it")
                migrationHelper.updateNetIgnoreRoles(it)
            } catch (Exception e) {
                log.error("Failed to remove $it", e)
            }
        }
    }
}
