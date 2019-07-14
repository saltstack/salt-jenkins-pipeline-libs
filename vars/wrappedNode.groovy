def call(String node_name,
         String gh_commit_status_context,
         String gh_commit_status_account,
         String display_name,
         Integer build_timeout,
         Closure body = null) {

    withSetBuildResult() {
        withSetGithubCommitContext(display_name, gh_commit_status_context, gh_commit_status_account) {
            node(node_name) {
                timeout(time: build_timeout, unit: 'HOURS') {
                    ansiColor('xterm') {
                        // Because we set that all pipelines will have timestamps in the Jenkins Main config,
                        // we comment out the timestamps closure bellow
                        // timestamps {
                            try {
                                if (body) { body() }
                            } finally {
                                sendSlackNotification()
                                cleanWs notFailBuild: true
                            }
                        //}
                    }
                }
            }
        }
    }

}

// vim: ft=groovy ts=4 sts=4 et
