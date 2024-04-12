package com.netgrif.mksr.startup


import com.netgrif.application.engine.auth.service.interfaces.IUserService
import com.netgrif.application.engine.petrinet.service.interfaces.IPetriNetService
import com.netgrif.application.engine.startup.AbstractOrderedCommandLineRunner
import com.netgrif.application.engine.workflow.service.interfaces.IWorkflowService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class CustomRunner extends AbstractOrderedCommandLineRunner {

    private static Logger log = LoggerFactory.getLogger(CustomRunner.class)

    @Autowired
    private IWorkflowService workflowService

    @Autowired
    private IUserService userService

    @Autowired
    private IPetriNetService petriNetService

    @Override
    void run(String... args) throws Exception {
        log.info("Calling EtaskRunner runner")
    }
}
