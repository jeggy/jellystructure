// DEB-4674 FR-13: Kotlin/JS 2.3 emits ES2020 syntax that Tizen 5.0's Chromium 63
// cannot parse. Transform every input module, including bundled npm packages, before
// webpack emits the production widget. The ES2017 parse guard checks the final file.
config.module = config.module || {};
config.module.rules = config.module.rules || [];
config.module.rules.push({
    test: /\.m?js$/,
    use: [{
        loader: 'babel-loader',
        options: {
            babelrc: false,
            configFile: false,
            presets: [['@babel/preset-env', {
                targets: { chrome: '63' },
                modules: false,
                bugfixes: true,
                useBuiltIns: false,
            }]],
        },
    }],
});
// Webpack's own bootstrap must obey the same syntax floor as its input modules.
config.output = config.output || {};
// The default UMD wrapper passes globalThis before application code runs. Chromium 63
// has self but not globalThis, so use the browser global for webpack's bootstrap.
config.output.globalObject = 'self';
config.output.environment = Object.assign({}, config.output.environment, {
    arrowFunction: true,
    asyncFunction: true,
    bigIntLiteral: false,
    const: true,
    destructuring: true,
    dynamicImport: false,
    forOf: true,
    module: false,
    optionalChaining: false,
    templateLiteral: true,
});
