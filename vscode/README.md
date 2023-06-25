# retro-lsp
LSP implementation for retro game development on the Super Nintendo Entertainment System using CA65.

## Features
### Customizable source directory
The setting `retroca65.sourceDirectory` allows the user to specify a directory relative to the source workspace as the "root" directory.

### libSFX support
The setting `retroca65.libSFXRoot`  allows the user to specify a directory relative to the source workspace as the "libSFXRoot" directory. 

### Go to definition support
Procs, labels, enums, structs, and macro references can be used to get the location of their definition.

### Go to included file support
Navigate to the file specified in the ca65 `.includes` control command in source files

### Auto complete file includes
When using the `.include` control command, the server provides completions for directories and files.

### Control command completion
Support for autocompleting many control commands

### SuperFX documentation hovers
Hovering over registers constants in `.sgs` files will display related documentation

### Syntax highlighting
Syntaxt highlighting and parsing provided by libSFX grammars

### Example vscode plugin
An example vscode plugin is provided in the .vscode directory

## Possible Future Features

### Dynamic help text
Import NaturalDocs syntax to show related help text to hovers

### Refactoring tools
Rename files, and symbols

### Go to usage support
Navigate from definitions to usages and references

### Improved everything
This first release is buggy, and many features are only half completed. General improvements include auto completion for appropriate control commands (similar to includes file picker).  Auto completion for constants and defined symbols will also be ideal.

## Development & Release
### Archetecture 
TODO, but this is an easy maven project.

In general, `CA65LanguageServer.java` initializes features and delegates to commands from the client them.

### JLink
The 'scripts' directory includes simple linking scripts for building jlink launchers for mac, linux, and windows.

### GrallVM Navive image
GrallVM support is in progress. Recent features broke the configuration and I haven't fixed it.

## Thanks and Contributions

Java Language Server - https://github.com/georgewfraser/java-language-server
GSON library - https://github.com/google/gson
ca65 - https://cc65.github.io/doc/ca65.html
libSFX - https://github.com/Optiroc/libSFX
