
def call(Map opts) {
    def env = opts.get('env')
    def String[] extra_parts = opts.get('extra_parts', [])

    def hostname_parts = [
        env.BUILD_TAG,
        extra_parts
    ]

    def String machine_hostname = hostname_parts.flatten().join('-')

    def Map replacements = [
        master: 'mst',
        zeromq: 'zmq',
        runtests: 'rt',
        pytest: 'pt',
        ubuntu: 'ubt',
        centos: 'cent',
        debian: 'deb',
        fedora: 'fed',
        windows: 'win',
        amazon: 'amzn',
        opensuse: 'osuse',
        m2crypto: 'm2c',
        pycryptodomex: 'pcdomex',
        tornado: 'trndo',
        macosx: 'mac',
        highsierra: 'hsierra',
        mojave: 'mjv'
    ]

    replacements.each { original, replacement ->
        machine_hostname = machine_hostname.replace(original, replacement)
    }

    // No Dots!
    machine_hostname = machine_hostname.replace('.', '_')

    return machine_hostname
}

// vim: ft=groovy ts=4 sts=4 et
