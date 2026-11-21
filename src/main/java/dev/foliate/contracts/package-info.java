/**
 * What the plugin's features offer each other: interfaces and the values they pass, never an
 * implementation. A module opens a menu through {@code Menus} without importing the zMenu hook,
 * and keeps working when zMenu is absent. Internal to the plugin, not an API for other plugins.
 * A feature's own subcommands need no contract: they live in its package.
 */
@NullMarked
package dev.foliate.contracts;

import org.jspecify.annotations.NullMarked;
