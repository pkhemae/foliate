/**
 * What the plugin's features offer each other: interfaces and the values they pass, never an
 * implementation. Empty in the template, whose features need nothing from each other: when one
 * does, give it a narrow interface here, one package per feature that offers it, which the other
 * feature implements; the one who needs it never imports the one who implements it. Internal to
 * the plugin, not an API for other plugins. A feature's own subcommands need no contract: they
 * live in its package.
 */
@NullMarked
package dev.foliate.contracts;

import org.jspecify.annotations.NullMarked;
