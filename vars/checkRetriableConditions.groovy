
def call(String filename) {

    def Boolean retry_condition_found = false

    // Let's check for SSH Timeouts
    def ssh_timeout_check_rc = sh(
        label: 'check-for-ssh-timeouts',
        returnStatus: true,
        script: """
        grep -q 'Connection timed out - recvfrom' ${filename}
        """
    )

    if ( ssh_timeout_check_rc == 0 ) {
        // The match succeeded
        retry_condition_found = true
        createSummary(
            id: 'ssh-timeout-warning',
            icon: 'warning.png',
            text: 'SSH Timeout Detected'
        )
    }

    // Let's check for a test suite exit code of 0 and a nox exit code of -9
    def testsuite_exitcode_check_rc = sh(
        label: 'check-exit-codes',
        returnStatus: true,
        script: """
        grep -q 'Test suite execution finalized with exit code: 0' ${filename}
        """
    )
    if ( testsuite_exitcode_check_rc == 0) {
        // Ok, the test suite reported a 0 exit code.
        // How about NOX?
        def nox_exitcode_check_rc = sh(
            label: 'check-exit-codes',
            returnStatus: true,
            script: """
            grep -q 'failed with exit code -9' ${filename}
            """
        )
        if ( nox_exitcode_check_rc == 0 ) {
            retry_condition_found = true
            createSummary(
                id: 'mismatch-exit-codes',
                icon: 'warning.png',
                text: 'Test suite reported exit code of 0 but nox failed with exit code -9'
            )
        }
    }

    return retry_condition_found
}
// vim: ft=groovy ts=4 sts=4 et
