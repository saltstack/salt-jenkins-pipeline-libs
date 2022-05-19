
def call(String nox_passthrough_opts,
         String test_paths,
         String chunk_name,
         String run_type,
         String python_version,
         String distro_version,
         String distro_arch,
         String distro_name,
         String test_suite_name_slug,
         Boolean upload_test_coverage,
         Boolean upload_split_test_coverage) {

    def Integer returnStatus = 1;

    def local_environ

    try {
        local_environ = [
            "NOX_PASSTHROUGH_OPTS=${nox_passthrough_opts} ${test_paths}"
        ]
        stage("Run ${chunk_name.capitalize()} Tests ${run_type}") {
            try {
                withEnv(local_environ) {
                    withEnv(["DONT_DOWNLOAD_ARTEFACTS=1"]) {
                        returnStatus = sh(
                            returnStatus: true,
                            label: "Run ${chunk_name.capitalize()} Tests",
                            script: 'bundle exec kitchen verify $TEST_SUITE-$TEST_PLATFORM; (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);'
                        )
                    }
                }
            } finally {
                sh """
                if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}.log" ]; then
                    mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}-${test_suite_name_slug}-${chunk_name}-verify.log"
                fi
                if [ -s ".kitchen/logs/kitchen.log" ]; then
                    mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-${test_suite_name_slug}-${chunk_name}-verify.log"
                fi
                """

                // Let's report about known problems found
                def List<String> conditions_found = []
                reportKnownProblems(conditions_found, ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}-${test_suite_name_slug}-${chunk_name}-verify.log")

                withEnv(["ONLY_DOWNLOAD_ARTEFACTS=1"]){
                    sh 'bundle exec kitchen verify $TEST_SUITE-$TEST_PLATFORM || exit 0'
                }
                sh """
                if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}.log" ]; then
                    mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}-${test_suite_name_slug}-${chunk_name}-download.log"
                fi
                if [ -s ".kitchen/logs/kitchen.log" ]; then
                    mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-${test_suite_name_slug}-${chunk_name}-download.log"
                fi
                if [ -s "artifacts/coverage/.coverage" ]; then
                    mv artifacts/coverage/.coverage artifacts/coverage/.coverage-${chunk_name}
                fi
                if [ -s "artifacts/coverage/salt.xml" ]; then
                    mv artifacts/coverage/salt.xml artifacts/coverage/salt-${chunk_name}.xml
                fi
                if [ -s "artifacts/coverage/tests.xml" ]; then
                    mv artifacts/coverage/tests.xml artifacts/coverage/tests-${chunk_name}.xml
                fi
                mv artifacts/logs/runtests-*.log artifacts/logs/${chunk_name}-runtests.log || true
                mv artifacts/xml-unittests-output/test-results-*.xml artifacts/xml-unittests-output/${chunk_name}-test-results.xml || true
                """
                sh """
                # Do not error if there are no files to compress
                xz .kitchen/logs/*-verify.log || true
                # Do not error if there are no files to compress
                xz artifacts/logs/${chunk_name}-runtests.log || true
                """
                if ( upload_test_coverage == true ) {
                    def distro_strings = [
                        distro_name,
                        distro_version,
                        distro_arch
                    ]
                    def report_strings = (
                        [python_version] + nox_env_name.split('-') + [chunk_name]
                    ).flatten()
                    if ( upload_split_test_coverage ) {
                        uploadCodeCoverage(
                            report_path: "artifacts/coverage/tests-${chunk_name}.xml",
                            report_name: "${distro_strings.join('-')}-${report_strings.join('-')}-tests",
                            report_flags: ([distro_strings.join('')] + report_strings + ['tests']).flatten()
                        )
                        uploadCodeCoverage(
                            report_path: "artifacts/coverage/salt-${chunk_name}.xml",
                            report_name: "${distro_strings.join('-')}-${report_strings.join('-')}-salt",
                            report_flags: ([distro_strings.join('')] + report_strings + ['salt']).flatten()
                        )
                    } else {
                        uploadCodeCoverage(
                            report_path: "artifacts/coverage/salt-${chunk_name}.xml",
                            report_name: "${distro_strings.join('-')}-${report_strings.join('-')}-salt",
                            report_flags: ([distro_strings.join('')] + report_strings).flatten()
                        )
                    }
                }
                archiveArtifacts(
                    artifacts: ".kitchen/logs/*-verify.log*,.kitchen/logs/*-download.log*,artifacts/logs/${chunk_name}-runtests.log*",
                    allowEmptyArchive: true
                )
                // Once archived, delete
                sh """
                rm .kitchen/logs/*-verify.log* .kitchen/logs/*-download.log* artifacts/logs/${chunk_name}-runtests.log* || true
                """
            }
        }
        stage("Re-Run Failed ${chunk_name.capitalize()} Tests ${run_type}") {
            if ( returnStatus != 0 ) {
                local_environ = [
                    "NOX_PASSTHROUGH_OPTS=${nox_passthrough_opts} ${test_paths} --lf"
                ]
                try {
                    withEnv(local_environ) {
                        withEnv(["DONT_DOWNLOAD_ARTEFACTS=1"]) {
                            returnStatus = sh(
                                returnStatus: true,
                                label: "Re-Run ${chunk_name.capitalize()} Tests",
                                script: 'bundle exec kitchen verify $TEST_SUITE-$TEST_PLATFORM; (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);'
                            )
                        }
                    }
                } finally {
                    sh """
                    if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}.log" ]; then
                        mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}-${test_suite_name_slug}-${chunk_name}-rerun-verify.log"
                    fi
                    if [ -s ".kitchen/logs/kitchen.log" ]; then
                        mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-${test_suite_name_slug}-${chunk_name}-rerun-verify.log"
                    fi
                    """

                    // Let's report about known problems found
                    def List<String> conditions_found_rerun = []
                    reportKnownProblems(conditions_found_rerun, ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}-${test_suite_name_slug}-${chunk_name}-rerun-verify.log")

                    withEnv(["ONLY_DOWNLOAD_ARTEFACTS=1"]){
                        sh 'bundle exec kitchen verify $TEST_SUITE-$TEST_PLATFORM || exit 0'
                    }
                    sh """
                    if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}.log" ]; then
                        mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}-${test_suite_name_slug}-${chunk_name}-rerun-download.log"
                    fi
                    if [ -s ".kitchen/logs/kitchen.log" ]; then
                        mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-${test_suite_name_slug}-${chunk_name}-rerun-download.log"
                    fi
                    if [ -s "artifacts/coverage/.coverage" ]; then
                        mv artifacts/coverage/.coverage artifacts/coverage/.coverage-rerun-${chunk_name}
                    fi
                    if [ -s "artifacts/coverage/salt.xml" ]; then
                        mv artifacts/coverage/salt.xml artifacts/coverage/salt-rerun-${chunk_name}.xml
                    fi
                    if [ -s "artifacts/coverage/tests.xml" ]; then
                        mv artifacts/coverage/tests.xml artifacts/coverage/tests-rerun-${chunk_name}.xml
                    fi
                    mv artifacts/logs/runtests-*.log artifacts/logs/${chunk_name}-rerun-runtests.log || true
                    mv artifacts/xml-unittests-output/test-results-*.xml artifacts/xml-unittests-output/${chunk_name}-rerun-test-results.xml || true
                    """
                    sh """
                    # Do not error if there are no files to compress
                    xz .kitchen/logs/*-verify.log || true
                    # Do not error if there are no files to compress
                    xz artifacts/logs/${chunk_name}-rerun-runtests.log || true
                    """
                    if ( upload_test_coverage == true ) {
                        def distro_strings = [
                            distro_name,
                            distro_version,
                            distro_arch
                        ]
                        def report_strings = (
                            [python_version] + nox_env_name.split('-') + [chunk_name]
                        ).flatten()
                        if ( upload_split_test_coverage ) {
                            uploadCodeCoverage(
                                report_path: "artifacts/coverage/tests-rerun-${chunk_name}.xml",
                                report_name: "${distro_strings.join('-')}-${report_strings.join('-')}-rerun-tests",
                                report_flags: ([distro_strings.join('')] + report_strings + ['tests']).flatten()
                            )
                            uploadCodeCoverage(
                                report_path: "artifacts/coverage/salt-rerun-${chunk_name}.xml",
                                report_name: "${distro_strings.join('-')}-${report_strings.join('-')}-rerun-salt",
                                report_flags: ([distro_strings.join('')] + report_strings + ['salt']).flatten()
                            )
                        } else {
                            uploadCodeCoverage(
                                report_path: "artifacts/coverage/salt-rerun-${chunk_name}.xml",
                                report_name: "${distro_strings.join('-')}-${report_strings.join('-')}-rerun-salt",
                                report_flags: ([distro_strings.join('')] + report_strings).flatten()
                            )
                        }
                    }
                    archiveArtifacts(
                        artifacts: ".kitchen/logs/*-verify.log*,.kitchen/logs/*-download.log,artifacts/logs/${chunk_name}-rerun-runtests.log*",
                        allowEmptyArchive: true
                    )
                    // Once archived, delete
                    sh """
                    rm .kitchen/logs/*-verify.log* .kitchen/logs/*-download.log* artifacts/logs/${chunk_name}-rerun-runtests.log* || true
                    """
                }
            }
        }
    } finally {
        archiveArtifacts(
            artifacts: "artifacts/*,artifacts/**/*,artifacts/xml-unittests-output/*.xml",
            allowEmptyArchive: true
        )
        junit(
            keepLongStdio: true,
            skipPublishingChecks: true,
            testResults: 'artifacts/xml-unittests-output/*.xml'
        )
        // Once archived, and reported, delete
        sh '''
        rm -rf artifacts/ || true
        '''
        return returnStatus
    }
}
