def call(Map options) {

    def env = options.get('env')
    def String distro_name = options.get('distro_name')
    def String distro_version = options.get('distro_version')
    def String distro_arch = options.get('distro_arch')
    def Integer concurrent_builds = options.get('concurrent_builds', 1)
    def String jenkins_slave_label = options.get('jenkins_slave_label', 'kitchen-slave')
    def String ec2_region = options.get('ec2_region', 'us-west-2')

    def Integer build_image_timeout_minutes = options.get('build_image_timeout_minutes', 60)

    // Packer
    def String packer_version = options.get("packer_version", "1.8.1")

    // AWS
    def String ami_image_id
    def String ami_name_filter

    // Vagrant
    def String vagrant_box_name
    def String vagrant_box_version
    def String vagrant_box_provider
    def String vagrant_box_name_testing

    def Boolean image_created = false
    def Boolean tests_passed = false
    def Boolean is_pr_build = isPRBuild()
    def String packer_staging_flag = ""

    if ( is_pr_build ) {
        packer_staging_flag = "--staging"
    }

    def Boolean macos_build = false
    if ( distro_name.startsWith("macos") ) {
        macos_build = true
    }

    // Enforce build concurrency
    enforceBuildConcurrency(options)
    // Now that we have enforced build concurrency, let's disable it for when calling runTests
    options['concurrent_builds'] = -1

    stage('Build Image') {
        ansiColor('xterm') {
            node(jenkins_slave_label) {
                try {
                    timeout(time: build_image_timeout_minutes, unit: 'MINUTES') {
                        timestamps {

                            // Checkout the repo
                            stage('Clone') {
                                checkout scm
                            }

                            if ( macos_build == false ) {
                                stage('Build AMI') {
                                    println "Using EC2 Region: ${ec2_region}"
                                    ansiColor('xterm') {
                                        withPackerVersion(packer_version) {
                                            sh """
                                            pyenv install 3.8.13 || echo "We already have this python."
                                            pyenv local 3.8.13
                                            if [ ! -d venv ]; then
                                                python -m venv venv
                                            fi
                                            . venv/bin/activate
                                            pip install -r os-images/requirements/py3.6/base.txt
                                            inv build-aws ${packer_staging_flag} --distro=${distro_name} --distro-version=${distro_version} --distro-arch=${distro_arch} --salt-pr=${env.CHANGE_ID} --region=${ec2_region}
                                            """
                                        }
                                    }
                                    ami_image_id = sh (
                                        script: """
                                        cat manifest.json|jq -r ".builds[].artifact_id"|cut -f2 -d:
                                        """,
                                        returnStdout: true
                                        ).trim()
                                    ami_name_filter = sh (
                                        script: """
                                        cat manifest.json|jq -r ".builds[].custom_data.ami_name"
                                        """,
                                        returnStdout: true
                                        ).trim()
                                    image_created = true
                                    ami_built_msg = "Built AMI ${ami_image_id}(${ami_name_filter})"
                                    addInfoBadge(
                                        id: 'build-ami-badge',
                                        text: ami_built_msg
                                    )
                                    createSummary(
                                        icon: "/images/svgs/attribute.svg",
                                        text: ami_built_msg
                                    )
                                }
                            } else {
                                stage('Build Vagrant Box') {
                                    ansiColor('xterm') {
                                        withPackerVersion(packer_version) {
                                            sh """
                                            pyenv install 3.8.13 || echo "We already have this python."
                                            pyenv local 3.8.13
                                            if [ ! -d venv ]; then
                                                python -m venv venv
                                            fi
                                            . venv/bin/activate
                                            pip install -r os-images/requirements/py3.6/base.txt
                                            inv build-osx ${packer_staging_flag} --distro-version=${distro_version} --distro-arch=${distro_arch} --salt-pr=${env.CHANGE_ID}
                                            """
                                        }
                                        vagrant_box_name = sh (
                                            script: """
                                            cat manifest.json|jq -r ".builds[].custom_data.box_name"
                                            """,
                                            returnStdout: true
                                            ).trim()
                                        vagrant_box_version = sh (
                                            script: """
                                            cat manifest.json|jq -r ".builds[].custom_data.box_version"
                                            """,
                                            returnStdout: true
                                            ).trim()
                                        vagrant_box_provider = sh (
                                            script: """
                                            cat manifest.json|jq -r ".builds[].custom_data.box_provider"
                                            """,
                                            returnStdout: true
                                            ).trim()
                                        vagrant_box_name_testing = sh (
                                            script: """
                                            cat manifest.json|jq -r ".builds[].custom_data.box_name_testing"
                                            """,
                                            returnStdout: true
                                            ).trim()
                                    }
                                    image_created = true
                                    ami_built_msg = "Built Vagrant Box ${vagrant_box_name}(${vagrant_box_version})"
                                    addInfoBadge(
                                        id: 'build-ami-badge',
                                        text: ami_built_msg
                                    )
                                    createSummary(
                                        icon: "/images/svgs/attribute.svg",
                                        text: ami_built_msg
                                    )
                                }
                            }
                        }
                    }
                } finally {
                    cleanWs notFailBuild: true
                }
            }
        }
    }

    if ( macos_build == false ) {
        options['ami_image_id'] = ami_image_id
        options['jenkins_slave_label'] = 'kitchen-slave'
    } else {
        options['vagrant_box'] = "${vagrant_box_name_testing}"
        options['vagrant_box_version'] = "${vagrant_box_version}"
        options['vagrant_box_provider'] = "${vagrant_box_provider}"
        options['jenkins_slave_label'] = 'kitchen-slave-mac'
    }
    options["force_run_full"] = true
    options["force_rerun_failed_tests"] = true
    options['upload_test_coverage'] = false

    def Boolean run_tests = true
    if ( image_created == true ) {
        try {
            if ( is_pr_build == false ) {
                if ( macos_build == false ) {
                    message = "${distro_name}-${distro_version}-${distro_arch} AMI `${ami_image_id}` is built. Skip tests?"
                } else {
                    message = "${distro_name}-${distro_version}-${distro_arch} Vagrant Box `${vagrant_box_name}` is built. Skip tests?"
                }
                try {
                    slack_message = "${message}\nPlease confirm or deny tests execution <${env.BUILD_URL}|here>"
                    slackSend(
                        channel: "#salt-golden-images",
                        color: '#FF8243',
                        message: slack_message)
                } catch (Exception e2) {
                    sh "echo Failed to send the Slack notification: ${e2}"
                }

                try {
                    timeout(time: 15, unit: 'MINUTES') {
                        run_tests = input(
                            id: 'run-tests',
                            message: "\n\n\n${message}\n",
                            parameters: [
                                booleanParam(
                                    defaultValue: true,
                                    description: 'Push the abort button to skip tests.',
                                    name: 'Please confirm you agree with this'
                                )
                            ]
                        )
                    }
                } catch(t_err) { // timeout reached or input false
                    def user = t_err.getCauses()[0].getUser()
                    if( 'SYSTEM' == user.toString() ) { // SYSTEM means timeout.
                        run_tests = true
                    } else {
                        run_tests = false
                        echo "Test suite execution skipped by: [${user}]"
                    }
                }
            }

            if ( run_tests == true ) {
                try {
                    runTests(options)
                    addInfoBadge id: 'py3-test-suite', text: "Py3 Test Suite against ${ami_image_id}(${ami_name_filter}) PASSED"
                    createSummary(icon: "/images/svgs/attribute.svg", text: "Py3 Test Suite against ${ami_image_id}(${ami_name_filter}) PASSED")
                } catch(Exception py3_err) {
                    addWarningBadge id: 'py3-test-suite', text: "Py3 Test Suite against ${ami_image_id}(${ami_name_filter}) FAILED"
                    createSummary(icon: "/images/svgs/attribute.svg", text: "Py3 Test Suite against ${ami_image_id}(${ami_name_filter}) FAILED")
                    throw py3_err
                }
                tests_passed = true
            } else {
                tests_passed = true
            }
        } finally {
            if ( is_pr_build == false ) {
                if ( macos_build == false ) {
                    stage('Promote AMI') {
                        try {
                            try {
                                message = "${distro_name}-${distro_version}-${distro_arch} AMI `${ami_image_id}` is waiting for CI duties promotion."
                                if (tests_passed) {
                                    message = "${message}\nTests Passed"
                                } else {
                                    message = "${message}\n*Tests Failed. Take extra care before promoting*."
                                }
                                message = "${message}\nPlease confirm or deny promotion <${env.BUILD_URL}|here>"
                                slackSend(
                                    channel: "#salt-golden-images",
                                    color: '#FF8243',
                                    message: message)
                            } catch (Exception e2) {
                                sh "echo Failed to send the Slack notification: ${e2}"
                            }
                            message = "${distro_name}-${distro_version}-${distro_arch} AMI `${ami_image_id}` is waiting for CI duties promotion."
                            if (tests_passed) {
                                message = "${message}\nTests Passed."
                            } else {
                                message = "${message}\nTests Failed. Take extra care before promoting."
                            }
                            timeout(time: 4, unit: 'HOURS') {
                                input id: 'promote-ami', message: "\n\n\n${message}\n\n\nPromote AMI ${ami_image_id}?\n", ok: 'Promote!'
                            }
                            node(jenkins_slave_label) {
                                try {
                                    checkout scm
                                    withPackerVersion(packer_version) {
                                        sh """
                                        pyenv install 3.8.13 || echo "We already have this python."
                                        pyenv local 3.8.13
                                        if [ ! -d venv ]; then
                                            python -m venv venv
                                        fi
                                        . venv/bin/activate
                                        pip install -r os-images/requirements/py3.6/base.txt
                                        inv promote-ami --image-id=${ami_image_id} --region=${ec2_region} --assume-yes
                                        """
                                    }
                                } finally {
                                    cleanWs notFailBuild: true
                                }
                            }
                            ami_built_msg = "AMI ${ami_image_id}(${ami_name_filter}) was promoted for CI duties!"
                            addBadge(
                                id: 'promoted-ami-badge',
                                icon: "/static/8361d0d6/images/svgs/accept.svg",
                                text: ami_built_msg
                            )
                            createSummary(
                                icon: "/images/svgs/accept.svg",
                                text: ami_built_msg
                            )
                            try {
                                slackSend(
                                    channel: "#salt-golden-images",
                                    color: '#00FF00',
                                    message: "${distro_name}-${distro_version}-${distro_arch} AMI `${ami_image_id}` was promoted! (<${env.BUILD_URL}|open>)")
                            } catch (Exception e3) {
                                sh "echo Failed to send the Slack notification: ${e3}"
                            }
                        } catch (Exception e4) {
                            println "AMI ${ami_image_id} was NOT promoted for CI duties! Reason: ${e4}"
                            addWarningBadge(
                                id: 'promoted-ami-badge',
                                text: "AMI ${ami_image_id}(${ami_name_filter}) was NOT promoted for CI duties!"
                            )
                            createSummary(
                                icon: "/images/svgs/warning.svg",
                                text: "AMI ${ami_image_id}(${ami_name_filter}) was <b>NOT</b> promoted for CI duties!"
                            )
                            try {
                                slackSend(
                                    channel: "#salt-golden-images",
                                    color: '#FF0000',
                                    message: "${distro_name}-${distro_version}-${distro_arch} AMI `${ami_image_id}` was *NOT* promoted! (<${env.BUILD_URL}|open>)")
                            } catch (Exception e5) {
                                sh "echo Failed to send the Slack notification: ${e5}"
                            }
                        }
                    }
                } else {
                    stage('Promote Vagrant Box') {
                        try {
                            message = "${distro_name}-${distro_version}-${distro_arch} Vagrant Box `${vagrant_box_name}` is waiting for CI duties promotion."
                            try {
                                if (tests_passed) {
                                    slack_message = "${message}\nTests Passed"
                                } else {
                                    slack_message = "${message}\n*Tests Failed. Take extra care before promoting*."
                                }
                                slack_message = "${slack_message}\nPlease confirm or deny promotion <${env.BUILD_URL}|here>"
                                slackSend(
                                    channel: "#salt-golden-images",
                                    color: '#FF8243',
                                    message: slack_message)
                            } catch (Exception e2) {
                                sh "echo Failed to send the Slack notification: ${e2}"
                            }
                            if (tests_passed) {
                                input_message = "${message}\nTests Passed."
                            } else {
                                imput_message = "${message}\nTests Failed. Take extra care before promoting."
                            }
                            timeout(time: 4, unit: 'HOURS') {
                                input(
                                    id: 'promote-box',
                                    message: "\n\n\n${input_message}\n\n\nPromote Vagrant Box ${vagrant_box_name}?\n",
                                    ok: 'Promote!'
                                )
                            }
                            node(jenkins_slave_label) {
                                try {
                                    checkout scm
                                    error "We currently can't upload built macOS images anywhere"
                                } finally {
                                    cleanWs notFailBuild: true
                                }
                            }
                            ami_built_msg = "Vagrant Box ${vagrant_box_name}(${vagrant_box_version}) was promoted for CI duties!"
                            addBadge(
                                id: 'promoted-ami-badge',
                                icon: "/static/8361d0d6/images/svgs/accept.svg",
                                text: ami_built_msg
                            )
                            createSummary(
                                icon: "/images/svgs/accept.svg",
                                text: ami_built_msg
                            )
                            try {
                                slackSend(
                                    channel: "#salt-golden-images",
                                    color: '#00FF00',
                                    message: "${distro_name}-${distro_version}-${distro_arch} Vagrant Box `${vagrant_box_name}(${vagrant_box_version})` was promoted! (<${env.BUILD_URL}|open>)")
                            } catch (Exception e3) {
                                sh "echo Failed to send the Slack notification: ${e3}"
                            }
                        } catch (Exception e4) {
                            error_message = "Vagrant Box ${vagrant_box_name}(${vagrant_box_version}) was NOT promoted for CI dutie!"
                            println "${error_message} Reason: ${e4}"
                            addWarningBadge(
                                id: 'promoted-ami-badge',
                                text: error_message
                            )
                            createSummary(
                                icon: "/images/svgs/warning.svg",
                                text: "Vagrant Box ${vagrant_box_name}(${vagrant_box_version}) was <b>NOT</b> promoted for CI duties!"
                            )
                            try {
                                slackSend(
                                    channel: "#salt-golden-images",
                                    color: '#FF0000',
                                    message: "${distro_name}-${distro_version}-${distro_arch} Vagrant Box ${vagrant_box_name}(${vagrant_box_version}) was *NOT* promoted! (<${env.BUILD_URL}|open>)")
                            } catch (Exception e5) {
                                sh "echo Failed to send the Slack notification: ${e5}"
                            }
                        }
                    }
                }
            }
        }
    }
}
// vim: ft=groovy ts=4 sts=4 et
