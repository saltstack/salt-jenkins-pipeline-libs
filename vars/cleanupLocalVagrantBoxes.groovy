def call(Map options) {

    def Integer global_timeout = options.get('global_timeout', 8)

    stage('Cleanup Local Vagrant Boxes') {
        vm_cleanup_seconds = global_timeout * 3600 + 3600
        sh """
        # Only remove running VMs here so we don't interfere with building golden images.
        # Stopped VMs will be cleaned up by a jenkins startup script when it connects.
        for i in `prlctl list -aij|jq -r '.[]|select((.Uptime|tonumber > ${vm_cleanup_seconds}) and (.State == "running"))|.ID'`
        do
            prlctl stop \${i} --kill || true
            prlctl delete \${i} || true
        done
        """
    }
}
