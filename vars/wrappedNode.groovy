def call(String node_name,
         Integer build_timeout,
         String slack_channel = null,
         Closure body = null) {

    ansiColor('xterm') {
        withSetBuildResult(slack_channel) {
            node(node_name) {
                timeout(time: build_timeout, unit: 'HOURS') {
                    // Because we set that all pipelines will have timestamps in the Jenkins Main config,
                    // we comment out the timestamps closure bellow
                    // timestamps {
                        try {
                            if (body) { body() }
                        } finally {
                            cleanWs notFailBuild: true
                        }
                    //}
                }
            }
        }
    }

}

// vim: ft=groovy ts=4 sts=4 et
