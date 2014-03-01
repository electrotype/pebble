/*******************************************************************************
 * This file is part of Pebble.
 * 
 * Original work Copyright (c) 2009-2013 by the Twig Team
 * Modified work Copyright (c) 2013 by Mitchell Bösecke
 * 
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 ******************************************************************************/
package com.mitchellbosecke.pebble.template;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.mitchellbosecke.pebble.PebbleEngine;
import com.mitchellbosecke.pebble.error.PebbleException;
import com.mitchellbosecke.pebble.node.ArgumentsNode;
import com.mitchellbosecke.pebble.node.RootNode;
import com.mitchellbosecke.pebble.utils.FutureWriter;

public class PebbleTemplateImpl implements PebbleTemplate {

	/**
	 * A template has to store a reference to the main engine so that it can
	 * compile other templates when using the "import" or "include" tags. It's
	 * important that the only method of the PebbleEngine that a template
	 * invokes during evaluation is the "getTemplate" method because this is the
	 * only one that I'm sure is thread-safe.
	 */
	protected final PebbleEngine engine;

	/**
	 * Blocks defined inside this template.
	 */
	private final Map<String, Block> blocks = new HashMap<>();

	/**
	 * Macros defined inside this template.
	 */
	private final Map<String, Macro> macros = new HashMap<>();

	private final RootNode rootNode;

	public PebbleTemplateImpl(PebbleEngine engine, RootNode root) throws PebbleException {
		this.engine = engine;
		this.rootNode = root;
	}

	public void buildContent(Writer writer, EvaluationContext context) throws IOException, PebbleException {
		rootNode.render(this, writer, context);
		if (context.getParent() != null) {
			context.pushInheritanceChain(this);
			PebbleTemplateImpl parent = context.getParent();
			context.setParent(null);
			parent.buildContent(writer, context);
			context.popInheritanceChain();
		}
	}

	public void evaluate(Writer writer) throws PebbleException, IOException {
		EvaluationContext context = initContext(null);
		evaluate(writer, context);
	}

	public void evaluate(Writer writer, Locale locale) throws PebbleException, IOException {
		EvaluationContext context = initContext(locale);
		evaluate(writer, context);
	}

	public void evaluate(Writer writer, Map<String, Object> map) throws PebbleException, IOException {
		EvaluationContext context = initContext(null);
		context.putAll(map);
		evaluate(writer, context);
	}

	public void evaluate(Writer writer, Map<String, Object> map, Locale locale) throws PebbleException, IOException {
		EvaluationContext context = initContext(locale);
		context.putAll(map);
		evaluate(writer, context);
	}

	/**
	 * This is the authoritative evaluate method. It should not be invoked by
	 * the end user and is therefore not included in the PebbleTemplate
	 * interface. I can't, however, make it "private" due to the fact that
	 * NodeInclude will call this method on a template other than itself.
	 * 
	 * 
	 * @param writer
	 * @param context
	 * @throws PebbleException
	 * @throws IOException
	 */
	public void evaluate(Writer writer, EvaluationContext context) throws PebbleException, IOException {
		if (context.getExecutorService() != null) {
			writer = new FutureWriter(writer, context.getExecutorService());
		}
		buildContent(writer, context);
		writer.flush();
	}

	/**
	 * Initializes the evaluation context with settings from the engine.
	 * 
	 * @param locale
	 * @return
	 */
	private EvaluationContext initContext(Locale locale) {
		locale = locale == null ? engine.getDefaultLocale() : locale;
		EvaluationContext context = new EvaluationContext(engine.isStrictVariables(), locale, engine.getFilters(),
				engine.getTests(), engine.getFunctions(), engine.getExecutorService());
		context.putAll(engine.getGlobalVariables());
		return context;
	}

	/**
	 * Imports a template.
	 * 
	 * @param template
	 * @throws PebbleException
	 */
	public void importTemplate(EvaluationContext context, String name) throws PebbleException {
		context.addImportedTemplate((PebbleTemplateImpl) engine.getTemplate(name));
	}

	public void includeTemplate(Writer writer, EvaluationContext context, String name) throws PebbleException,
			IOException {
		PebbleTemplateImpl template = (PebbleTemplateImpl) engine.getTemplate(name);
		template.evaluate(writer, context);
	}

	public boolean hasMacro(String macroName) {
		return macros.containsKey(macroName);
	}

	/**
	 * Registers a block.
	 * 
	 * @param block
	 */
	public void registerBlock(Block block) {
		blocks.put(block.getName(), block);
	}

	public boolean hasBlock(String blockName) {
		return blocks.containsKey(blockName);
	}

	public void registerMacro(Macro macro) throws PebbleException {
		if(macros.containsKey(macro.getName())){
			throw new PebbleException(null, "More than one macro can not share the same name: " + macro.getName());
		}
		this.macros.put(macro.getName(), macro);
	}

	/**
	 * A typical block declaration will use this method which evaluates the
	 * block using the regular user-provided writer.
	 * 
	 * @param blockName
	 * @param context
	 * @param ignoreOverriden
	 * @param writer
	 * @throws PebbleException
	 * @throws IOException
	 */
	public void block(String blockName, EvaluationContext context, boolean ignoreOverriden, Writer writer)
			throws PebbleException, IOException {

		PebbleTemplateImpl childTemplate = context.getChildTemplate();

		// check child
		if (!ignoreOverriden && childTemplate != null && childTemplate.hasBlock(blockName)) {
			context.popInheritanceChain();
			childTemplate.block(blockName, context, false, writer);
			context.pushInheritanceChain(childTemplate);

			// check this template
		} else if (blocks.containsKey(blockName)) {
			Block block = blocks.get(blockName);
			block.evaluate(this, writer, context);

			// delegate to parent
		} else {
			if (context.getParent() != null) {
				context.pushInheritanceChain(this);
				context.getParent().block(blockName, context, true, writer);
				context.popInheritanceChain();
			}
		}

	}

	public String macro(String macroName, EvaluationContext context, ArgumentsNode args) throws PebbleException {
		String result = null;
		boolean found = false;

		PebbleTemplateImpl childTemplate = context.getChildTemplate();

		// check child template first
		if (childTemplate != null && childTemplate.hasMacro(macroName)) {
			found = true;
			context.popInheritanceChain();
			result = childTemplate.macro(macroName, context, args);
			context.pushInheritanceChain(childTemplate);

			// check current template
		} else if (hasMacro(macroName)) {
			found = true;
			Macro macro = macros.get(macroName);

			Map<String, Object> namedArguments = args.getArgumentMap(this, context, macro);
			result = macro.call(this, context, namedArguments);
		}

		// check imported templates
		if (!found) {
			for (PebbleTemplateImpl template : context.getImportedTemplates()) {
				if (template.hasMacro(macroName)) {
					found = true;
					result = template.macro(macroName, context, args);
				}
			}
		}

		// delegate to parent template
		if (!found) {
			if (context.getParent() != null) {
				context.pushInheritanceChain(this);
				result = context.getParent().macro(macroName, context, args);
				context.popInheritanceChain();
			} else {
				throw new PebbleException(null, String.format("Function or Macro [%s] does not exist.", macroName));
			}
		}

		return result;
	}

	public void setParent(EvaluationContext context, String parentName) throws PebbleException {
		context.setParent((PebbleTemplateImpl) engine.getTemplate(parentName));
	}

}
