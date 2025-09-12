# idea-run-wdio

![Build](https://github.com/wenqingzhang/idea-run-wdio/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/16147.svg)](https://plugins.jetbrains.com/plugin/16147)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/16147.svg)](https://plugins.jetbrains.com/plugin/16147)


<!-- Plugin description -->
This plugin integrates [WebdriverIO](https://webdriver.io/) with JetBrains IDEs, allowing you to run your tests
seamlessly from within the editor. It uses the official [@wdio/cli](https://webdriver.io/docs/gettingstarted) test
runner to execute your spec files.

**Note:** This plugin currently only supports the [Mocha framework](https://webdriver.io/docs/frameworks#mocha).

## Features

- **Run tests with a single click:** Gutter icons allow you to run an entire test file or a specific `describe` or `it`
  block.
- **Automatic Configuration:** The plugin automatically detects your `wdio.conf.js` file and uses it for test execution.
- **Run Configuration Generation:** Automatically creates and manages Run/Debug configurations for your tests.
<!-- Plugin description end -->

## Installation

- Using IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "
  WebdriverIO"</kbd> >
  <kbd>Install Plugin</kbd>

- Manually:

  Download the [latest release](https://github.com/wenqingzhang/idea-run-wdio/releases/latest) and install it manually
  using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>
