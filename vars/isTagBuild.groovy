import hudson.model.Job
import jenkins.scm.api.mixin.TagSCMHead
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty

@NonCPS
Boolean isTagBuild(Job build_parent) {
    build_parent.getProperty(BranchJobProperty).branch.head in TagSCMHead
}

Boolean call() {
    isTagBuild(currentBuild.rawBuild.parent)
}
