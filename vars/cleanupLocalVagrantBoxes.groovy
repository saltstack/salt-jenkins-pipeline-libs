def call(Map options) {
    stage('Cleanup Local Vagrant Boxes') {
        sh '''
        for i in `prlctl list -aij|jq -r '.[]|select((.Uptime|tonumber > 86400) and (.State == "running"))|.ID'`
        do
            prlctl stop $i --kill
        done
        # don't delete vm's that haven't started yet ((.State == "stopped") and (.Uptime == "0"))
        for i in `prlctl list -aij|jq -r '.[]|select((.Uptime|tonumber > 0) and (.State != "running"))|.ID'`
        do
            prlctl delete $i
        done
        '''
    }
}
