// SPDX-License-Identifier: GPL-3.0-or-later
'use strict'

/**
 * Lets one spelling of a Mermaid block work in both places it is read.
 *
 * GitHub renders Mermaid inside .adoc only from `[source,mermaid]`. asciidoctor-kroki
 * renders only `[mermaid]`. Writing either one breaks the other, and these diagrams
 * are read in both — in the repository while working, on the site while reading.
 *
 * So the files keep `[source,mermaid]`, which is the form that survives being browsed
 * on GitHub, and this rewrites it to `[mermaid]` in the aggregated content, after the
 * files are read and before Asciidoctor sees them. Nothing on disk changes.
 *
 * Deliberately a string replacement on the block declaration line rather than a
 * parsed transformation: the declaration is unambiguous, and a tree processor would
 * have to reproduce what asciidoctor-kroki already does with the block it is handed.
 */
module.exports.register = function () {
  this.on('contentAggregated', ({ contentAggregate }) => {
    let rewritten = 0
    contentAggregate.forEach(({ files }) => {
      files.forEach((file) => {
        if (!file.path.endsWith('.adoc')) return
        const source = file.contents.toString()
        if (!source.includes('[source,mermaid]')) return
        rewritten += source.split('[source,mermaid]').length - 1
        file.contents = Buffer.from(source.replaceAll('[source,mermaid]', '[mermaid]'))
      })
    })
    if (rewritten) {
      this.getLogger('mermaid-source-blocks').info(
        `rewrote ${rewritten} [source,mermaid] blocks to [mermaid] for Kroki`
      )
    }
  })
}
