
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
         Boolean upload_split_test_coverage,
         Boolean rerun_failed_tests) {

    def Integer returnStatus = 1;

    def local_environ

    stage("Run ${chunk_name.capitalize()} Tests ${run_type}") {
        try {
            local_environ = [
                "NOX_PASSTHROUGH_OPTS=${nox_passthrough_opts} -o \"junit_suite_name=${chunk_name.capitalize()} Tests ${run_type}\" ${test_paths}"
            ]
            deleteRemoteArtifactsDir()
            try {
                withEnv(local_environ) {
                    withEnv(["DONT_DOWNLOAD_ARTEFACTS=1"]) {
                        sh(
                            label: "Run ${chunk_name.capitalize()} Tests ${run_type}",
                            script: 'bundle exec kitchen verify $TEST_SUITE-$TEST_PLATFORM; (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);'
                        )
                    }
                }
                returnStatus = 0
            } catch (run_error) {
                returnStatus = 1
                if ( rerun_failed_tests == true ) {
                    echo "Failed to run ${chunk_name.capitalize()} Tests ${run_type}. Re-trying failed tests."
                } else {
                    error "Failed to run ${chunk_name.capitalize()} Tests ${run_type}."
                    throw run_error
                }
            } finally {
                sh label: 'Rename logs', script: """
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
                sh label: 'Rename logs', script: """
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
                mv artifacts/logs/\$(ls artifacts/logs/ | sort -r | head -n 1) artifacts/logs/${chunk_name}-runtests.log || true
                rm artifacts/logs/runtests-*.log || true
                mv artifacts/xml-unittests-output/\$(ls artifacts/xml-unittests-output | sort -r | head -n 1) artifacts/xml-unittests-output/${chunk_name}-test-results.xml || true
                rm artifacts/xml-unittests-output/test-results-*.xml || true
                """
                sh label: 'Compress logs', script: """
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
                            report_flags: ([distro_strings.join('-')] + report_strings + ['tests']).flatten()
                        )
                        uploadCodeCoverage(
                            report_path: "artifacts/coverage/salt-${chunk_name}.xml",
                            report_name: "${distro_strings.join('-')}-${report_strings.join('-')}-salt",
                            report_flags: ([distro_strings.join('-')] + report_strings + ['salt']).flatten()
                        )
                    } else {
                        uploadCodeCoverage(
                            report_path: "artifacts/coverage/salt-${chunk_name}.xml",
                            report_name: "${distro_strings.join('-')}-${report_strings.join('-')}-salt",
                            report_flags: ([distro_strings.join('-')] + report_strings).flatten()
                        )
                    }
                }
                archiveArtifacts(
                    artifacts: ".kitchen/logs/*-verify.log*,.kitchen/logs/*-download.log*,artifacts/logs/${chunk_name}-runtests.log*",
                    allowEmptyArchive: true
                )
                // Once archived, delete
                sh label: 'Delete archived logs', script: """
                rm .kitchen/logs/*-verify.log* .kitchen/logs/*-download.log* artifacts/logs/${chunk_name}-runtests.log* || true
                """

                if ( returnStatus != 0 && rerun_failed_tests == true ) {
                    deleteRemoteArtifactsDir()
                    local_environ = [
                        "NOX_PASSTHROUGH_OPTS=${nox_passthrough_opts} -o \"junit_suite_name=${chunk_name.capitalize()} Tests ${run_type} (Re-run Failed)\" ${test_paths} --lf"
                    ]
                    try {
                        withEnv(local_environ) {
                            withEnv(["DONT_DOWNLOAD_ARTEFACTS=1"]) {
                                sh(
                                    label: "Re-Run ${chunk_name.capitalize()} Tests",
                                    script: 'bundle exec kitchen verify $TEST_SUITE-$TEST_PLATFORM; (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);'
                                )
                            }
                        }
                        returnStatus = 0
                    } catch (rerun_error) {
                        returnStatus = 1
                        error "Failed to re-run ${chunk_name.capitalize()} Tests ${run_type}."
                        throw rerun_error
                    } finally {
                        sh label: 'Rename logs', script: """
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
                        sh label: 'Rename logs', script: """
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
                        mv artifacts/logs/\$(ls artifacts/logs/ | sort -r | head -n 1) artifacts/logs/${chunk_name}-rerun-runtests.log || true
                        rm artifacts/logs/runtests-*.log || true
                        mv artifacts/xml-unittests-output/\$(ls artifacts/xml-unittests-output | sort -r | head -n 1) artifacts/xml-unittests-output/${chunk_name}-rerun-test-results.xml || true
                        rm artifacts/xml-unittests-output/test-results-*.xml || true
                        """
                        sh label: 'Compress logs', script: """
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
                                    report_flags: ([distro_strings.join('-')] + report_strings + ['tests']).flatten()
                                )
                                uploadCodeCoverage(
                                    report_path: "artifacts/coverage/salt-rerun-${chunk_name}.xml",
                                    report_name: "${distro_strings.join('-')}-${report_strings.join('-')}-rerun-salt",
                                    report_flags: ([distro_strings.join('-')] + report_strings + ['salt']).flatten()
                                )
                            } else {
                                uploadCodeCoverage(
                                    report_path: "artifacts/coverage/salt-rerun-${chunk_name}.xml",
                                    report_name: "${distro_strings.join('-')}-${report_strings.join('-')}-rerun-salt",
                                    report_flags: ([distro_strings.join('-')] + report_strings).flatten()
                                )
                            }
                        }
                        archiveArtifacts(
                            artifacts: ".kitchen/logs/*-verify.log*,.kitchen/logs/*-download.log,artifacts/logs/${chunk_name}-rerun-runtests.log*",
                            allowEmptyArchive: true
                        )
                        // Once archived, delete
                        sh label: 'Delete archived logs', script: """
                        rm .kitchen/logs/*-verify.log* .kitchen/logs/*-download.log* artifacts/logs/${chunk_name}-rerun-runtests.log* || true
                        """
                    }
                }
            }
        } finally {
            try {
                archiveArtifacts(
                    artifacts: "artifacts/*,artifacts/**/*,artifacts/xml-unittests-output/*.xml",
                    allowEmptyArchive: true
                )
            } finally {
                try {
                    junit(
                        keepLongStdio: true,
                        skipPublishingChecks: true,
                        testResults: 'artifacts/xml-unittests-output/*.xml'
                    )
                } finally {
                    // Once archived, and reported, delete
                    sh label: 'Delete downloaded artifacts', script: '''
                    rm -rf artifacts/ || true
                    '''
                    try {
                        if ( returnStatus != 0 ) {
                            currentBuild.result = 'FAILURE'
                            if ( rerun_failed_tests == true ) {
                                error "Failed to run(and re-run failed) ${chunk_name.capitalize()} Tests ${run_type}."
                            } else {
                                error "Failed to run ${chunk_name.capitalize()} Tests ${run_type}."
                            }
                        }
                    } finally {
                        return returnStatus
                    }
                }
            }
        }
    }
}
