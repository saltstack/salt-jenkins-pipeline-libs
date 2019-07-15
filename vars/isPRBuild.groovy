import hudson.model.Job
import jenkins.scm.api.mixin.ChangeRequestSCMHead
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty

@NonCPS
Boolean isPRBuild(Job build_parent) {
    build_parent.getProperty(BranchJobProperty).branch.head in ChangeRequestSCMHead
}

Boolean call() {
    isPRBuild(currentBuild.rawBuild.parent)
}
