
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

    return retry_condition_found
}
// vim: ft=groovy ts=4 sts=4 et
