
def call(String display_name, String gh_commit_status_context, String gh_commit_status_account, Closure body=null) {

    def gh_commit_status_set = false

    try {
        githubNotify credentialsId: gh_commit_status_account,
            description: "Testing ${display_name} starts...",
            status: 'PENDING',
            context: gh_commit_status_context
        // Run the closure body
        if (body) { body() }
    } catch(InterruptedException ie) {
        githubNotify credentialsId: gh_commit_status_account,
            description: "Testing ${display_name} interrupted...",
            status: 'ERROR',
            context: gh_commit_status_context
        gh_commit_status_set = true
        throw ie
    } catch(Exception e) {
        githubNotify credentialsId: gh_commit_status_account,
            description: "Testing ${display_name} errored...",
            status: 'ERROR',
            context: gh_commit_status_context
        gh_commit_status_set = true
        throw e
    } finally {
        if (!gh_commit_status_set) {
            if (currentBuild.resultIsBetterOrEqualTo('SUCCESS')) {
                githubNotify credentialsId: gh_commit_status_account,
                    description: "Testing ${display_name} succeeded",
                    status: 'SUCCESS',
                    context: gh_commit_status_context
            } else {
                githubNotify credentialsId: gh_commit_status_account,
                    description: "Testing ${display_name} failed",
                    status: 'FAILURE',
                    context: gh_commit_status_context
            }
        }
    }
}
// vim: ft=groovy ts=4 sts=4 et
