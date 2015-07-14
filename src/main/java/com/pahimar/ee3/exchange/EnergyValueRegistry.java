package com.pahimar.ee3.exchange;

import com.google.common.collect.ImmutableSortedMap;
import com.google.gson.*;
import com.pahimar.ee3.api.exchange.EnergyValue;
import com.pahimar.ee3.api.exchange.EnergyValueRegistryProxy;
import com.pahimar.ee3.api.exchange.IEnergyValueProvider;
import com.pahimar.ee3.configuration.EnergyRegenOption;
import com.pahimar.ee3.filesystem.FileSystem;
import com.pahimar.ee3.filesystem.IFileSystem;
import com.pahimar.ee3.recipe.RecipeRegistry;
import com.pahimar.ee3.reference.Files;
import com.pahimar.ee3.reference.Settings;
import com.pahimar.ee3.serialization.EnergyValueStackMappingSerializer;
import com.pahimar.ee3.serialization.JsonSerialization;
import com.pahimar.ee3.util.EnergyValueHelper;
import com.pahimar.ee3.util.LoaderHelper;
import com.pahimar.ee3.util.LogHelper;
import com.pahimar.ee3.util.SerializationHelper;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

import javax.naming.OperationNotSupportedException;
import java.io.File;
import java.lang.reflect.Type;
import java.util.*;

public class EnergyValueRegistry implements JsonSerializer<EnergyValueRegistry>, JsonDeserializer<EnergyValueRegistry>
{
    // TODO Rethink serialization here
    private static final Gson JSON_SERIALIZER = (new GsonBuilder()).setPrettyPrinting()
            .registerTypeAdapter(EnergyValueRegistry.class, new EnergyValueRegistry())
            .registerTypeAdapter(EnergyValueStackMapping.class, new EnergyValueStackMappingSerializer()).create();

    private static final Object singletonSyncRoot = new Object();

    // TODO Expose to API
    private static final IEnergyValuesSource[] preCalculationSources = {
            new FileSystemEnergyValuesSource(Files.PRE_CALCULATION_ENERGY_VALUES),
            new FileSystemEnergyValuesSource(Files.PRE_CALCULATION_ENERGY_VALUES, true)
    };

    private static final IEnergyValuesSource[] postCalculationSources = {
            new FileSystemEnergyValuesSource(Files.POST_CALCULATION_ENERGY_VALUES),
            new FileSystemEnergyValuesSource(Files.POST_CALCULATION_ENERGY_VALUES, true)
    };

    private boolean shouldRegenNextRestart = false;
    private static EnergyValueRegistry energyValueRegistry = null;
    private static Map<WrappedStack, EnergyValue> preCalculationMappings;
    private static Map<WrappedStack, EnergyValue> postCalculationMappings;
    private ImmutableSortedMap<WrappedStack, EnergyValue> stackMappings;
    private ImmutableSortedMap<EnergyValue, List<WrappedStack>> valueMappings;
    private SortedSet<WrappedStack> uncomputedStacks;

    private EnergyValueRegistry()
    {
    }

    public static EnergyValueRegistry getInstance()
    {
        if (energyValueRegistry == null)
        {
            synchronized (singletonSyncRoot) {
                if(energyValueRegistry == null)
                    energyValueRegistry = new EnergyValueRegistry();
            }
        }

        return energyValueRegistry;
    }

    public void addPreCalculationEnergyValue(Object object, float energyValue)
    {
        addPreCalculationEnergyValue(object, new EnergyValue(energyValue));
    }

    public void addPreCalculationEnergyValue(Object object, EnergyValue energyValue)
    {
        if (preCalculationMappings == null)
        {
            preCalculationMappings = new TreeMap<WrappedStack, EnergyValue>();
        }

        if (WrappedStack.canBeWrapped(object) && energyValue != null && Float.compare(energyValue.getValue(), 0f) > 0)
        {
            WrappedStack wrappedStack = WrappedStack.wrap(object);

            if (wrappedStack.getStackSize() > 0)
            {
                WrappedStack factoredWrappedStack = WrappedStack.wrap(wrappedStack, 1);
                EnergyValue factoredEnergyValue = EnergyValueHelper.factorEnergyValue(energyValue, wrappedStack.getStackSize());

                if (preCalculationMappings.containsKey(factoredWrappedStack))
                {
                    if (factoredEnergyValue.compareTo(preCalculationMappings.get(factoredWrappedStack)) < 0)
                    {
                        LogHelper.trace(String.format("EnergyValueRegistry[%s]: Mod with ID '%s' added a pre-assignment energy value of %s for object %s", LoaderHelper.getLoaderState(), Loader.instance().activeModContainer().getModId(), energyValue, wrappedStack));
                        preCalculationMappings.put(factoredWrappedStack, factoredEnergyValue);
                    }
                }
                else
                {
                    LogHelper.trace(String.format("EnergyValueRegistry[%s]: Mod with ID '%s' added a pre-assignment energy value of %s for object %s", LoaderHelper.getLoaderState(), Loader.instance().activeModContainer().getModId(), energyValue, wrappedStack));
                    preCalculationMappings.put(factoredWrappedStack, factoredEnergyValue);
                }
            }
        }
    }

    public void addPostCalculationExactEnergyValue(Object object, float energyValue)
    {
        addPostCalculationExactEnergyValue(object, new EnergyValue(energyValue));
    }

    public void addPostCalculationExactEnergyValue(Object object, EnergyValue energyValue)
    {
        if (postCalculationMappings == null)
        {
            postCalculationMappings = new TreeMap<WrappedStack, EnergyValue>();
        }

        if (WrappedStack.canBeWrapped(object) && energyValue != null && Float.compare(energyValue.getValue(), 0f) > 0)
        {
            WrappedStack wrappedStack = WrappedStack.wrap(object);

            if (wrappedStack.getStackSize() > 0)
            {
                WrappedStack factoredWrappedStack = WrappedStack.wrap(wrappedStack, 1);
                EnergyValue factoredEnergyValue = EnergyValueHelper.factorEnergyValue(energyValue, wrappedStack.getStackSize());

                LogHelper.trace(String.format("EnergyValueRegistry[%s]: Mod with ID '%s' added a post-assignment energy value of %s for object %s", LoaderHelper.getLoaderState(), Loader.instance().activeModContainer().getModId(), energyValue, wrappedStack));
                postCalculationMappings.put(factoredWrappedStack, factoredEnergyValue);
            }
        }
    }

    public boolean hasEnergyValue(Object object)
    {
        return hasEnergyValue(object, false);
    }

    public boolean hasEnergyValue(Object object, boolean strict)
    {
        return getEnergyValue(object, strict) != null;
    }

    public EnergyValue getEnergyValue(Object object)
    {
        return getEnergyValue(EnergyValueRegistryProxy.Phase.ALL, object, false);
    }

    public EnergyValue getEnergyValue(Object object, boolean strict)
    {
        return getEnergyValue(EnergyValueRegistryProxy.Phase.ALL, object, strict);
    }

    public EnergyValue getEnergyValue(EnergyValueRegistryProxy.Phase phase, Object object, boolean strict)
    {
        if (phase == EnergyValueRegistryProxy.Phase.PRE_ASSIGNMENT || phase == EnergyValueRegistryProxy.Phase.PRE_CALCULATION)
        {
            return getEnergyValueFromMap(preCalculationMappings, object, strict);
        }
        else if (phase == EnergyValueRegistryProxy.Phase.POST_ASSIGNMENT || phase == EnergyValueRegistryProxy.Phase.POST_CALCULATION)
        {
            return getEnergyValueFromMap(postCalculationMappings, object, strict);
        }
        else
        {
            return getEnergyValueFromMap(energyValueRegistry.stackMappings, object, strict);
        }
    }

    public EnergyValue getEnergyValueForStack(Object object, boolean strict)
    {
        WrappedStack wrappedObject = WrappedStack.wrap(object);

        if (wrappedObject != null && getEnergyValue(object, strict) != null)
        {
            return new EnergyValue(getEnergyValue(object, strict).getValue() * wrappedObject.getStackSize());
        }

        return null;
    }

    public EnergyValue getEnergyValueFromMap(Map<WrappedStack, EnergyValue> stackEnergyValueMap, Object object)
    {
        return getEnergyValueFromMap(stackEnergyValueMap, object, false);
    }

    public EnergyValue getEnergyValueFromMap(Map<WrappedStack, EnergyValue> stackEnergyValueMap, Object object, boolean strict)
    {
        if (WrappedStack.canBeWrapped(object))
        {
            WrappedStack wrappedStackObject = WrappedStack.wrap(object);
            WrappedStack unitWrappedStackObject = WrappedStack.wrap(object);
            unitWrappedStackObject.setStackSize(1);
            Object wrappedObject = wrappedStackObject.getWrappedObject();

            /**
             *  In the event that an Item has an IEnergyValueProvider implementation, route the call to the implementation
             */
            if (wrappedObject instanceof ItemStack && ((ItemStack) wrappedObject).getItem() instanceof IEnergyValueProvider && !strict)
            {
                ItemStack itemStack = (ItemStack) wrappedObject;
                IEnergyValueProvider iEnergyValueProvider = (IEnergyValueProvider) itemStack.getItem();
                EnergyValue energyValue = iEnergyValueProvider.getEnergyValue(itemStack);

                if (energyValue != null && energyValue.getValue() > 0f)
                {
                    return energyValue;
                }
            }
            else if (stackEnergyValueMap != null)
            {
                /**
                 *  Check for a direct value mapping for the object
                 */
                if (stackEnergyValueMap.containsKey(unitWrappedStackObject))
                {
                    return stackEnergyValueMap.get(unitWrappedStackObject);
                }
                else if (!strict)
                {
                    if (wrappedObject instanceof ItemStack)
                    {
                        EnergyValue lowestValue = null;
                        ItemStack wrappedItemStack = (ItemStack) wrappedObject;

                        /**
                         *  The ItemStack does not have a direct mapping, so check if it is a member of an OreDictionary
                         *  entry. If it is a member of an OreDictionary entry, check if every ore name it is associated
                         *  with has 1) a direct mapping, and 2) the same mapping value
                         */
                        if (OreDictionary.getOreIDs(wrappedItemStack).length >= 1)
                        {
                            EnergyValue energyValue = null;
                            boolean allHaveSameValueFlag = true;

                            // Scan all valid ore dictionary values, if they ALL have the same value, then return it
                            for (int oreID : OreDictionary.getOreIDs(wrappedItemStack))
                            {
                                String oreName = OreDictionary.getOreName(oreID);
                                if (!oreName.equals("Unknown"))
                                {
                                    WrappedStack oreStack = WrappedStack.wrap(new OreStack(oreName));

                                    if (oreStack != null && stackEnergyValueMap.containsKey(oreStack))
                                    {
                                        if (energyValue == null)
                                        {
                                            energyValue = stackEnergyValueMap.get(oreStack);
                                        }
                                        else if (!energyValue.equals(stackEnergyValueMap.get(oreStack)))
                                        {
                                            allHaveSameValueFlag = false;
                                        }
                                    }
                                    else
                                    {
                                        allHaveSameValueFlag = false;
                                    }
                                }
                                else
                                {
                                    allHaveSameValueFlag = false;
                                }
                            }

                            if (energyValue != null && allHaveSameValueFlag)
                            {
                                return energyValue;
                            }
                        }
                        else
                        {
                            /**
                             *  Scan the stack value map for ItemStacks that have the same Item. If one is found, check
                             *  if it has a wildcard meta value (and therefore is considered the same). Otherwise, check
                             *  if the ItemStack is "damageable" and calculate the value for the damaged stack.
                             */
                            for (WrappedStack valuedStack : stackEnergyValueMap.keySet())
                            {
                                if (valuedStack.getWrappedObject() instanceof ItemStack)
                                {
                                    ItemStack valuedItemStack = (ItemStack) valuedStack.getWrappedObject();

                                    if (Item.getIdFromItem(valuedItemStack.getItem()) == Item.getIdFromItem(wrappedItemStack.getItem()))
                                    {
                                        if (valuedItemStack.getItemDamage() == OreDictionary.WILDCARD_VALUE || wrappedItemStack.getItemDamage() == OreDictionary.WILDCARD_VALUE)
                                        {
                                            EnergyValue stackValue = stackEnergyValueMap.get(valuedStack);

                                            if (stackValue.compareTo(lowestValue) < 0)
                                            {
                                                lowestValue = stackValue;
                                            }
                                        }
                                        else if (wrappedItemStack.getItem().isDamageable() && wrappedItemStack.isItemDamaged())
                                        {
                                            EnergyValue stackValue = new EnergyValue(stackEnergyValueMap.get(valuedStack).getValue() * (1 - (wrappedItemStack.getItemDamage() * 1.0F / wrappedItemStack.getMaxDamage())));

                                            if (stackValue.compareTo(lowestValue) < 0)
                                            {
                                                lowestValue = stackValue;
                                            }
                                        }
                                    }
                                }
                            }

                            return lowestValue;
                        }
                    }
                    else if (wrappedObject instanceof OreStack)
                    {
                        OreStack oreStack = (OreStack) wrappedObject;

                        if (CachedOreDictionary.getInstance().getItemStacksForOreName(oreStack.oreName).size() >= 1)
                        {
                            EnergyValue energyValue = null;
                            boolean allHaveSameValueFlag = true;

                            // Scan all valid ore dictionary values, if they ALL have the same value, then return it
                            for (ItemStack itemStack : CachedOreDictionary.getInstance().getItemStacksForOreName(oreStack.oreName))
                            {
                                WrappedStack wrappedItemStack = WrappedStack.wrap(itemStack);

                                if (wrappedItemStack != null && stackEnergyValueMap.containsKey(wrappedItemStack))
                                {
                                    if (energyValue == null)
                                    {
                                        energyValue = stackEnergyValueMap.get(wrappedItemStack);
                                    }
                                    else if (!energyValue.equals(stackEnergyValueMap.get(wrappedItemStack)))
                                    {
                                        allHaveSameValueFlag = false;
                                    }
                                }
                                else
                                {
                                    allHaveSameValueFlag = false;
                                }
                            }

                            if (energyValue != null && allHaveSameValueFlag)
                            {
                                return energyValue;
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    protected final void init()
            throws OperationNotSupportedException
    {
        if(this.shouldRegenerateEnergyValue())
            this.runDynamicEnergyValueResolution();

        this.shouldRegenNextRestart = false;
    }

    protected boolean shouldRegenerateEnergyValue()
    {
        if(Settings.DynamicEnergyValueGeneration.regenerateEnergyValuesWhen == EnergyRegenOption.Always)
            return true;

        return !this.loadEnergyValueRegistryFromFile();
    }

    // TODO Refactor, break into stages and break passes too.
    private void runDynamicEnergyValueResolution()
            throws OperationNotSupportedException
    {
        IRegistryContext context = new Context(this);
        IEnergyCalculationDataProvider dataProvider = new CalculationDataProvider();
        EnergyCalculationSession session = new EnergyCalculationSession(context, dataProvider);

        EnergyCalculationSession.Result result = session.runDynamicEnergyValueResolution();
        this.stackMappings = result.getStackValueMap();

        /**
         *  Value map resolution
         */
        generateValueStackMappings();

        // Serialize values to disk
        LogHelper.info("Saving energy values to disk");
        save();

        // TODO Make this make "sense" and also ensure it's added as an option to the debug command
        if(this.uncomputedStacks != null)
        {
            LogHelper.info("BEGIN UNCOMPUTED OBJECT LIST");
            for (WrappedStack wrappedStack : uncomputedStacks)
            {
                if (!hasEnergyValue(wrappedStack))
                {
                    LogHelper.info(wrappedStack);
                }
            }
            LogHelper.info("END UNCOMPUTED OBJECT LIST");
        }
    }

    private void generateValueStackMappings()
    {
        SortedMap<EnergyValue, List<WrappedStack>> tempValueMappings = new TreeMap<EnergyValue, List<WrappedStack>>();

        for (WrappedStack stack : stackMappings.keySet())
        {
            if (stack != null)
            {
                EnergyValue value = stackMappings.get(stack);

                if (value != null)
                {
                    if (tempValueMappings.containsKey(value))
                    {
                        if (!(tempValueMappings.get(value).contains(stack)))
                        {
                            tempValueMappings.get(value).add(stack);
                        }
                    }
                    else
                    {
                        tempValueMappings.put(value, new ArrayList<WrappedStack>(Arrays.asList(stack)));
                    }
                }
            }
        }
        valueMappings = ImmutableSortedMap.copyOf(tempValueMappings);
    }

    private Map<WrappedStack, EnergyValue> computeStackMappings(Map<WrappedStack, EnergyValue> stackValueMappings, int passCount)
    {
        Map<WrappedStack, EnergyValue> computedStackMap = new TreeMap<WrappedStack, EnergyValue>();

        for (WrappedStack recipeOutput : RecipeRegistry.getInstance().getRecipeMappings().keySet())
        {
            // TODO Review: possible fault in the logic here that is preventing some values from being assigned?
            if (!hasEnergyValue(recipeOutput.getWrappedObject(), false) && !computedStackMap.containsKey(recipeOutput))
            {
                EnergyValue lowestValue = null;

                for (List<WrappedStack> recipeInputs : RecipeRegistry.getInstance().getRecipeMappings().get(recipeOutput))
                {
                    EnergyValue computedValue = EnergyValueHelper.computeEnergyValueFromRecipe(stackValueMappings, recipeOutput, recipeInputs);

                    if (computedValue != null)
                    {
                        if (computedValue.compareTo(lowestValue) < 0)
                        {
                            lowestValue = computedValue;
                        }
                    }
                    else
                    {
                        if (uncomputedStacks == null)
                        {
                            uncomputedStacks = new TreeSet<WrappedStack>();
                        }

                        uncomputedStacks.add(recipeOutput);
                    }
                }

                if ((lowestValue != null) && (lowestValue.getValue() > 0f))
                {
                    computedStackMap.put(WrappedStack.wrap(recipeOutput.getWrappedObject()), lowestValue);
                }
            }
        }

        return computedStackMap;
    }

    public List getStacksInRange(int start, int finish)
    {
        return getStacksInRange(new EnergyValue(start), new EnergyValue(finish));
    }

    public List getStacksInRange(float start, float finish)
    {
        return getStacksInRange(new EnergyValue(start), new EnergyValue(finish));
    }

    public List getStacksInRange(EnergyValue start, EnergyValue finish)
    {
        List stacksInRange = new ArrayList<WrappedStack>();

        if (valueMappings != null)
        {
            SortedMap<EnergyValue, List<WrappedStack>> tailMap = energyValueRegistry.valueMappings.tailMap(start);
            SortedMap<EnergyValue, List<WrappedStack>> headMap = energyValueRegistry.valueMappings.headMap(finish);

            SortedMap<EnergyValue, List<WrappedStack>> smallerMap;
            SortedMap<EnergyValue, List<WrappedStack>> biggerMap;

            if (!tailMap.isEmpty() && !headMap.isEmpty())
            {

                if (tailMap.size() <= headMap.size())
                {
                    smallerMap = tailMap;
                    biggerMap = headMap;
                }
                else
                {
                    smallerMap = headMap;
                    biggerMap = tailMap;
                }

                for (EnergyValue value : smallerMap.keySet())
                {
                    if (biggerMap.containsKey(value))
                    {
                        for (WrappedStack wrappedStack : energyValueRegistry.valueMappings.get(value))
                        {
                            if (wrappedStack.getWrappedObject() instanceof ItemStack || wrappedStack.getWrappedObject() instanceof FluidStack)
                            {
                                stacksInRange.add(wrappedStack.getWrappedObject());
                            }
                            else if (wrappedStack.getWrappedObject() instanceof OreStack)
                            {
                                for (ItemStack itemStack : OreDictionary.getOres(((OreStack) wrappedStack.getWrappedObject()).oreName))
                                {
                                    stacksInRange.add(itemStack);
                                }
                            }
                        }
                    }
                }
            }
        }

        return stacksInRange;
    }

    public void loadFromMap(Map<WrappedStack, EnergyValue> stackValueMap)
    {
        if (stackValueMap != null)
        {
            ImmutableSortedMap.Builder<WrappedStack, EnergyValue> stackMappingsBuilder = ImmutableSortedMap.naturalOrder();
            stackMappingsBuilder.putAll(stackValueMap);
            stackMappings = stackMappingsBuilder.build();

            /**
             *  Resolve value stack mappings from the newly loaded stack mappings
             */
            generateValueStackMappings();
        }
    }

    public void setEnergyValue(WrappedStack wrappedStack, EnergyValue energyValue)
    {
        if (wrappedStack != null && energyValue != null && Float.compare(energyValue.getValue(), 0f) > 0)
        {
            TreeMap<WrappedStack, EnergyValue> stackValueMap = new TreeMap<WrappedStack, EnergyValue>(stackMappings);
            stackValueMap.put(wrappedStack, energyValue);

            ImmutableSortedMap.Builder<WrappedStack, EnergyValue> stackMappingsBuilder = ImmutableSortedMap.naturalOrder();
            stackMappingsBuilder.putAll(stackValueMap);
            stackMappings = stackMappingsBuilder.build();

            generateValueStackMappings();
        }
    }

    public boolean getShouldRegenNextRestart()
    {
        return shouldRegenNextRestart;
    }

    public void setShouldRegenNextRestart(boolean shouldRegenNextRestart)
    {
        this.shouldRegenNextRestart = shouldRegenNextRestart;
    }

    public ImmutableSortedMap<WrappedStack, EnergyValue> getStackValueMap()
    {
        return stackMappings;
    }

    public ImmutableSortedMap<EnergyValue, List<WrappedStack>> getValueStackMap()
    {
        return valueMappings;
    }

    public void save()
    {
        World world = FMLCommonHandler.instance().getMinecraftServerInstance().getEntityWorld();
        File energyValuesDataDirectory = FileSystem.getWorld(world).getEnergyValuesDirectory();
        energyValuesDataDirectory.mkdirs();

        if (shouldRegenNextRestart)
        {
            File staticEnergyValuesJsonFile = new File(energyValuesDataDirectory, Files.STATIC_ENERGY_VALUES_JSON);
            File md5EnergyValuesJsonFile = new File(energyValuesDataDirectory, SerializationHelper.getModListMD5() + ".json");

            // JSON
            if (staticEnergyValuesJsonFile.exists())
            {
                staticEnergyValuesJsonFile.delete();
            }
            if (md5EnergyValuesJsonFile.exists())
            {
                md5EnergyValuesJsonFile.delete();
            }

            shouldRegenNextRestart = false;
        }
        else
        {
            SerializationHelper.compressEnergyValueStackMapToFile(new File(energyValuesDataDirectory, Files.STATIC_ENERGY_VALUES_JSON), energyValueRegistry.stackMappings);
            SerializationHelper.compressEnergyValueStackMapToFile(new File(energyValuesDataDirectory, SerializationHelper.getModListMD5() + ".json.gz"), energyValueRegistry.stackMappings);
        }
    }

    public boolean loadEnergyValueRegistryFromFile()
    {
        World world = FMLCommonHandler.instance().getMinecraftServerInstance().getEntityWorld();
        IFileSystem fileSystem = FileSystem.getWorld(world);
        File energyValuesDataDirectory = fileSystem.getEnergyValuesDirectory();
        energyValuesDataDirectory.mkdirs();

        File staticEnergyValuesFile = fileSystem.getStaticEnergyValueFile();
        File md5EnergyValuesFile = fileSystem.getEnergyValueFile(SerializationHelper.getModListMD5() + ".json.gz");

        Map<WrappedStack, EnergyValue> stackValueMap = null;
        if (Settings.DynamicEnergyValueGeneration.regenerateEnergyValuesWhen != EnergyRegenOption.Always)
        {
            if (Settings.DynamicEnergyValueGeneration.regenerateEnergyValuesWhen == EnergyRegenOption.ModsChange)
            {
                if (md5EnergyValuesFile.exists())
                {
                    LogHelper.info("Attempting to load energy values from file: " + md5EnergyValuesFile.getAbsolutePath());
                    stackValueMap = SerializationHelper.decompressEnergyValueStackMapFromFile(md5EnergyValuesFile);
                }
            }
            else if (Settings.DynamicEnergyValueGeneration.regenerateEnergyValuesWhen == EnergyRegenOption.Never)
            {
                if (staticEnergyValuesFile.exists())
                {
                    LogHelper.info("Attempting to load energy values from file: " + staticEnergyValuesFile.getAbsolutePath());
                    stackValueMap = SerializationHelper.decompressEnergyValueStackMapFromFile(staticEnergyValuesFile);
                }
                else if (md5EnergyValuesFile.exists())
                {
                    LogHelper.info("Attempting to load energy values from file: " + md5EnergyValuesFile.getAbsolutePath());
                    stackValueMap = SerializationHelper.decompressEnergyValueStackMapFromFile(md5EnergyValuesFile);
                }
            }

            if (stackValueMap != null)
            {
                loadFromMap(stackValueMap);
                LogHelper.info("Successfully loaded energy values from file");
                return true;
            }
            else
            {
                LogHelper.info("No energy value file to load values from, generating new values");
                return false;
            }
        }
        else
        {
            return false;
        }
    }

    public String toJson()
    {
        return JSON_SERIALIZER.toJson(this);
    }

    @Override
    public EnergyValueRegistry deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException
    {
        if (json.isJsonArray())
        {
            JsonArray jsonArray = (JsonArray) json;
            Map<WrappedStack, EnergyValue> stackValueMap = new TreeMap<WrappedStack, EnergyValue>();
            Iterator<JsonElement> iterator = jsonArray.iterator();

            while (iterator.hasNext())
            {
                JsonElement jsonElement = iterator.next();
                EnergyValueStackMapping energyValueStackMapping = new EnergyValueStackMappingSerializer().deserialize(jsonElement, typeOfT, context);

                if (energyValueStackMapping != null)
                {
                    stackValueMap.put(energyValueStackMapping.wrappedStack, energyValueStackMapping.energyValue);
                }
            }

            ImmutableSortedMap.Builder<WrappedStack, EnergyValue> stackMappingsBuilder = ImmutableSortedMap.naturalOrder();
            stackMappingsBuilder.putAll(stackValueMap);
            stackMappings = stackMappingsBuilder.build();

            generateValueStackMappings();
        }

        return null;
    }

    @Override
    public JsonElement serialize(EnergyValueRegistry energyValueRegistry, Type typeOfSrc, JsonSerializationContext context)
    {
        JsonArray jsonEnergyValueRegistry = new JsonArray();

        for (WrappedStack wrappedStack : energyValueRegistry.stackMappings.keySet())
        {
            jsonEnergyValueRegistry.add(JsonSerialization.jsonSerializer.toJsonTree(new EnergyValueStackMapping(wrappedStack, energyValueRegistry.stackMappings.get(wrappedStack))));
        }

        return jsonEnergyValueRegistry;
    }

    public void dumpEnergyValueRegistryToLog()
    {
        dumpEnergyValueRegistryToLog(EnergyValueRegistryProxy.Phase.ALL);
    }

    public void dumpEnergyValueRegistryToLog(EnergyValueRegistryProxy.Phase phase)
    {
        LogHelper.info(String.format("BEGIN DUMPING %s ENERGY VALUE MAPPINGS", phase));
        if (phase == EnergyValueRegistryProxy.Phase.PRE_ASSIGNMENT || phase == EnergyValueRegistryProxy.Phase.PRE_CALCULATION)
        {
            for (WrappedStack wrappedStack : this.preCalculationMappings.keySet())
            {
                LogHelper.info(String.format("- Object: %s, Value: %s", wrappedStack, EnergyValueRegistry.getInstance().getStackValueMap().get(wrappedStack)));
            }
        }
        else if (phase == EnergyValueRegistryProxy.Phase.POST_ASSIGNMENT || phase == EnergyValueRegistryProxy.Phase.POST_CALCULATION)
        {
            if (this.postCalculationMappings != null)
            {
                for (WrappedStack wrappedStack : this.postCalculationMappings.keySet())
                {
                    LogHelper.info(String.format("- Object: %s, Value: %s", wrappedStack, EnergyValueRegistry.getInstance().getStackValueMap().get(wrappedStack)));
                }
            }
        }
        else if (phase == EnergyValueRegistryProxy.Phase.ALL)
        {
            for (WrappedStack wrappedStack : EnergyValueRegistry.getInstance().getStackValueMap().keySet())
            {
                LogHelper.info(String.format("- Object: %s, Value: %s", wrappedStack, EnergyValueRegistry.getInstance().getStackValueMap().get(wrappedStack)));
            }
        }
        LogHelper.info(String.format("END DUMPING %s ENERGY VALUE MAPPINGS", phase));
    }

    private class Context implements IRegistryContext
    {
        private final EnergyValueRegistry registry;
        private final IFileSystem globalFs;
        private final IFileSystem worldFs;

        private Context(EnergyValueRegistry registry) throws OperationNotSupportedException
        {
            this.registry = registry;
            this.globalFs = FileSystem.getGlobal();

            World world = FMLCommonHandler.instance().getMinecraftServerInstance().getEntityWorld();
            this.worldFs = FileSystem.getWorld(world);
        }

        @Override
        public boolean hasEnergyValue(Object object, boolean strict)
        {
            return this.registry.hasEnergyValue(object, strict);
        }

        @Override
        public IFileSystem getGlobal()
        {
            return this.globalFs;
        }

        @Override
        public IFileSystem getWorld()
        {
            return this.worldFs;
        }
    }

    private class CalculationDataProvider implements IEnergyCalculationDataProvider
    {
        private CalculationDataProvider()
        {
        }

        @Override
        public Map<WrappedStack, EnergyValue> getPreCalculationMappings()
        {
            return EnergyValueRegistry.preCalculationMappings;
        }

        @Override
        public Map<WrappedStack, EnergyValue> getPostCalculationMappings()
        {
            return EnergyValueRegistry.postCalculationMappings;
        }

        @Override
        public IEnergyValuesSource[] getPreCalculationSources()
        {
            return EnergyValueRegistry.preCalculationSources;
        }

        @Override
        public IEnergyValuesSource[] getPostCalculationSources()
        {
            return EnergyValueRegistry.postCalculationSources;
        }
    }
}
