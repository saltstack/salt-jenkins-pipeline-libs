
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
         Boolean rerun_in_progress) {

    def Integer returnStatus = 1;

    def local_environ;
    def String stage_name;
    def String chunk_name_filename;
    def Boolean skip_marking_build_unstable = true;

    try {
        if ( rerun_in_progress ) {
            chunk_name_filename = "rerun-${chunk_name_filename}"
            stage_name = "${chunk_name.capitalize()} Tests ${run_type} (Re-run Failed)"
            local_environ = [
                "NOX_PASSTHROUGH_OPTS=${nox_passthrough_opts} -o \"junit_suite_name=${stage_name}\" --lf ${test_paths}"
            ]
        } else {
            chunk_name_filename = chunk_name
            stage_name = "${chunk_name.capitalize()} Tests ${run_type}"
            local_environ = [
                "NOX_PASSTHROUGH_OPTS=${nox_passthrough_opts} -o \"junit_suite_name=${stage_name}\" ${test_paths}"
            ]
        }
    } catch (Exception e1) {
        error("runTestsChunk:e1: Caught error while defining stage name and local environ: ${e1}")
        return returnStatus
    }


    try {
        stage("Run ${stage_name}") {
            echo "=======> Running ${stage_name} =======>"
            deleteRemoteArtifactsDir()
            try {
                catchError(
                    buildResult: 'SUCCESS',
                    stageResult: 'FAILURE',
                    message: "Failed to run ${stage_name}"
                ) {
                    withEnv(local_environ) {
                        withEnv(["DONT_DOWNLOAD_ARTEFACTS=1"]) {
                            sh(
                                label: "Run ${stage_name}",
                                script: 'bundle exec kitchen verify $TEST_SUITE-$TEST_PLATFORM; (exitcode=$?; echo "ExitCode: $exitcode"; exit $exitcode);'
                            )
                        }
                    }
                    returnStatus = 0
                }
            } finally {
                try {
                    def kitchen_dst_short_log_filename = "${test_suite_name_slug}-${chunk_name_filename}"
                    if ( rerun_in_progress ) {
                        chunk_name_filename = "rerun-${chunk_name_filename}"
                        kitchen_dst_short_log_filename = "${kitchen_dst_short_log_filename}-rerun"
                    }
                    def kitchen_src_log_filename = "${python_version}-${distro_name}-${distro_version}-${distro_arch}"
                    def kitchen_dst_log_filename = "${kitchen_src_log_filename}-${kitchen_dst_short_log_filename}"
                    sh label: 'Rename verify logs', script: """
                    if [ -s ".kitchen/logs/${kitchen_src_log_filename}.log" ]; then
                        mv ".kitchen/logs/${kitchen_src_log_filename}.log" ".kitchen/logs/${kitchen_dst_log_filename}-verify.log"
                    fi
                    if [ -s ".kitchen/logs/kitchen.log" ]; then
                        mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-${kitchen_dst_short_log_filename}-verify.log"
                    fi
                    """

                    // Let's report about known problems found
                    def List<String> conditions_found = []
                    reportKnownProblems(conditions_found, ".kitchen/logs/${kitchen_dst_log_filename}-verify.log")

                    withEnv(["ONLY_DOWNLOAD_ARTEFACTS=1"]){
                        sh 'bundle exec kitchen verify $TEST_SUITE-$TEST_PLATFORM || exit 0'
                    }
                    sh label: 'Rename download logs', script: """
                    if [ -s ".kitchen/logs/${kitchen_src_log_filename}.log" ]; then
                        mv ".kitchen/logs/${kitchen_src_log_filename}.log" ".kitchen/logs/${kitchen_dst_log_filename}-download.log"
                    fi
                    if [ -s ".kitchen/logs/kitchen.log" ]; then
                        mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-${kitchen_dst_short_log_filename}-download.log"
                    fi
                    """
                    sh label: 'Rename coverage artifacts', script: """
                    if [ -s "artifacts/coverage/.coverage" ]; then
                        mv artifacts/coverage/.coverage artifacts/coverage/.coverage-${chunk_name_filename}
                    fi
                    if [ -s "artifacts/coverage/salt.xml" ]; then
                        mv artifacts/coverage/salt.xml artifacts/coverage/salt-${chunk_name_filename}.xml
                    fi
                    if [ -s "artifacts/coverage/tests.xml" ]; then
                        mv artifacts/coverage/tests.xml artifacts/coverage/tests-${chunk_name_filename}.xml
                    fi
                    mv artifacts/logs/\$(ls artifacts/logs/ | sort -r | head -n 1) artifacts/logs/${chunk_name_filename}-runtests.log || true
                    rm artifacts/logs/runtests-*.log || true
                    mv \$(ls artifacts/xml-unittests-output/test-results-*.xml | sort -r | head -n 1) artifacts/xml-unittests-output/${chunk_name_filename}-test-results.xml || true
                    rm artifacts/xml-unittests-output/test-results-*.xml || true
                    ls -lah artifacts/xml-unittests-output/ || true
                    """
                    sh label: 'Compress logs', script: """
                    # Do not error if there are no files to compress
                    xz .kitchen/logs/*-verify.log || true
                    # Do not error if there are no files to compress
                    xz artifacts/logs/${chunk_name_filename}-runtests.log || true
                    """
                } catch (Exception e2) {
                    echo "runTestsChunk:e2: Error processing artifacts: ${e2}"
                }
                try {
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
                            def report_name_part = ""
                            if ( rerun_in_progress ) {
                                report_name_part = "rerun-"
                            }
                            uploadCodeCoverage(
                                report_path: "artifacts/coverage/tests-${chunk_name_filename}.xml",
                                report_name: "${distro_strings.join('-')}-${report_strings.join('-')}-${report_name_part}tests",
                                report_flags: ([distro_strings.join('-')] + report_strings + ['tests']).flatten()
                            )
                            uploadCodeCoverage(
                                report_path: "artifacts/coverage/salt-${chunk_name_filename}.xml",
                                report_name: "${distro_strings.join('-')}-${report_strings.join('-')}-${report_name_part}salt",
                                report_flags: ([distro_strings.join('-')] + report_strings + ['salt']).flatten()
                            )
                        } else {
                            uploadCodeCoverage(
                                report_path: "artifacts/coverage/salt-${chunk_name_filename}.xml",
                                report_name: "${distro_strings.join('-')}-${report_strings.join('-')}-${report_name_part}salt",
                                report_flags: ([distro_strings.join('-')] + report_strings).flatten()
                            )
                        }
                    }
                } catch (Exception e3) {
                    echo "runTestsChunk:e3: Error uploading code coverage: ${e3}"
                } finally {
                    archiveArtifacts(
                        artifacts: ".kitchen/logs/*-verify.log*,.kitchen/logs/*-download.log*,artifacts/logs/${chunk_name_filename}-runtests.log*",
                        allowEmptyArchive: true
                    )
                    // Once archived, delete
                    sh label: 'Delete archived logs', script: """
                    rm .kitchen/logs/*-verify.log* .kitchen/logs/*-download.log* artifacts/logs/${chunk_name_filename}-runtests.log* || true
                    """
                }
            }
        }
    } catch (Exception e4) {
        error("runTestsChunk:e4: Error processing chunk: ${e4}")
        throw e4
    } finally {
        echo "<======= Finished running ${stage_name} <======="
        return returnStatus
    }
}
