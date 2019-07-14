def call(String node_name,
         String display_name,
         String checkout_directory,
         String nox_passthrough_opts = '',
         Integer integration_modules_chunks,
         Integer integration_states_chunks,
         Integer unit_chunks,
         Integer other_chunks,
         String gh_commit_status_context,
         String gh_commit_status_account,
         Integer parallel_testrun_timeout,
         Integer serial_testrun_timeout,
         Integer testsuite_timeout,
         String timeout_unit = 'HOURS') {

    def chunks = [:]
    nox_passthrough_opts = "--log-cli-level=warning --ignore=tests/utils ${nox_passthrough_opts}".trim()

    // Integration Module Tests
    for (int i=1; i<(integration_modules_chunks+1); i++) {
        def chunk_no = i
        def stagename = "Integration Modules #${chunk_no}"
        def env_array = [
            "NOX_PASSTHROUGH_OPTS=${nox_passthrough_opts} --test-group-count=$integration_modules_chunks --test-group=$chunk_no tests/integration/modules"
        ]
        chunks[stagename] = runTests(checkout_directory, stagename, env_array, parallel_testrun_timeout)
    }

    // Integration State Tests
    for (int i=1; i<(integration_states_chunks+1); i++) {
        def chunk_no = i
        def stagename = "Integration States #${chunk_no}"
        def env_array = [
            "NOX_PASSTHROUGH_OPTS=${nox_passthrough_opts} --test-group-count=$integration_states_chunks --test-group=$chunk_no tests/integration/states"
        ]
        chunks[stagename] = runTests(checkout_directory, stagename, env_array, parallel_testrun_timeout)
    }

    // Unit Tests
    for (int i=1; i<(unit_chunks+1); i++) {
        def chunk_no = i
        def stagename = "Unit #${chunk_no}"
        def env_array = [
            "NOX_PASSTHROUGH_OPTS=${nox_passthrough_opts} --test-group-count=$unit_chunks --test-group=$chunk_no tests/unit"
        ]
        chunks[stagename] = runTests(checkout_directory, stagename, env_array, parallel_testrun_timeout)
    }

    // All Other
    for (int i=1; i<(other_chunks+1); i++) {
        def chunk_no = i
        def stagename = "All Other #${chunk_no}"
        def env_array = [
            "NOX_PASSTHROUGH_OPTS=${nox_passthrough_opts} --test-group-count=$other_chunks --test-group=$chunk_no --ignore=tests/integration/modules --ignore=tests/integration/states --ignore=tests/unit"
        ]
        chunks[stagename] = runTests(checkout_directory, stagename, env_array, parallel_testrun_timeout)
    }

    wrappedNode(node_name, gh_commit_status_context, gh_commit_status_account, build_timeout) {

        dir(checkout_directory) {
            // Checkout the repo
            stage('Clone') {
                cleanWs notFailBuild: true
                checkout scm
                sh 'git fetch --no-tags https://github.com/saltstack/salt.git +refs/heads/${SALT_TARGET_BRANCH}:refs/remotes/origin/${SALT_TARGET_BRANCH}'
            }

            // Setup the kitchen required bundle
            stage('Setup') {
                sh 'bundle install --with ec2 windows --without docker macos opennebula vagrant'
            }
        }

        stage('Parallel Test Run') {
            parallel chunks
        }

        stage('Serial Test Run') {
            runTests(
                checkout_directory,
                'Full Test Suite',
                ["NOX_PASSTHROUGH_OPTS=${nox_passthrough_opts} tests/"],
                serial_testrun_timeout
            )
        }

    }

}
// vim: ft=groovy ts=4 sts=4 et
