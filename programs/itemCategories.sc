//Script for importing and exporting item categories by FIdoubleN
//Credit to CommandLeo (https://github.com/CommandLeo/) for helping me

global_quote = '\'';
global_path = 'categories/';
global_containers = ['shulker_box', 'chest', 'trapped_chest', 'barrel'];

__config() -> {
  'commands' -> {
    'help' -> 'help',

    'import <sourceFile>' -> ['importCategories', 'shulker_box', 27, 1, false],
    'import <sourceFile> <container>' -> ['importCategories', 27, 1, false],
    'import <sourceFile> <container> <shulkerFillLevel>' -> ['importCategories', 1, false],
    'import <sourceFile> <container> <shulkerFillLevel> <itemCount>' -> ['importCategories', false],
    'import <sourceFile> <container> <shulkerFillLevel> <itemCount> <proportionalStackSize>' -> 'importCategories',

    'export <destinationFile> <from_pos> <to_pos>' -> 'exportCategories',

    'create_folders' -> 'createFolders'
  },
  'arguments' -> {
    'sourceFile' -> {
        'type' -> 'string',
        'suggester' -> _(args) -> map(list_files(global_path, 'shared_json'), slice(_, length(global_path)))
    },
    'container' -> {'type' -> 'term', 'suggest' -> global_containers},
    'shulkerFillLevel' -> {'type' -> 'int', 'min' -> 1, 'max' -> 27, 'suggest' -> [27]},
    'itemCount' -> {'type' -> 'int', 'min' -> 1, 'max' -> 64, 'suggest' -> [1]},
    'proportionalStackSize' -> {'type' -> 'bool'},

    'destinationFile' -> {'type' -> 'string', 'suggest' -> ['myCategories']},
    'from_pos' -> {'type' -> 'pos', 'loaded' -> true},
    'to_pos' -> {'type' -> 'pos', 'loaded' -> true},
  }
};


//Helper

//Prints usage
help() -> (
    texts = [
        'fs ' + ' ' * 80, ' \n',
        '#1ECB74b Item Categories', ' \n\n',
        '#26DE81 /app_name import <sourceFile> [<container>] [<shulkerFillLevel>] [<itemCount>] ', 'f ｜ ', 'g ' +
        'Imports item categories from JSON file, file has to contain at least the "items" object.', ' \n',
        '#26DE81 /app_name export <destinationFile> <from_pos> <to_pos>', 'f ｜ ', 'g ' +
        'Exports categories to file, categories are read from 1x1 row of shulker boxes, box color changes indicate new categories.', ' \n',
        '#26DE81 /app_name create_folders ', 'f ｜ ', 'g ' +
        'Utility that creates the "shared/categories/" folder. Import files should be pasted into this folder.', ' \n',
        'fs ' + ' ' * 80
    ];
    print(format(map(texts, replace(_, 'app_name', system_info('app_name')))));
);

//Error function, exits program after printing message
//
//@param message error message
_error(message) -> exit(print(format(str('r %s', message))));

//Warning function, prints warning message
//
//@param message warning message
_warning(message) -> print(format(str('y WARNING: %s', message)));

//Get list of all positions in the area between 2 positions, area needs to be a 1x1 row
//
//@param from_pos starting position
//@param to_pos end position
//@return list containing all positions in row
_scanStrip(from_pos, to_pos) -> (
    [x1, y1, z1] = from_pos;
    [x2, y2, z2] = to_pos;
    [dx, dy, dz] = map(to_pos - from_pos, if(_ < 0, -1, 1));
    if(x1 != x2, return(map(range(x1, x2 + dx, dx), [_, y1, z1]))); //Add dx because 'to' parameter of range is exclusive
    if(y1 != y2, return(map(range(y1, y2 + dy, dy), [x1, _, z1])));
    if(z1 != z2, return(map(range(z1, z2 + dz, dz), [x1, y1, _])));
);

//Get list of shulker box inventory
//
//@param pos position of shulker box
//@return list of all non-empty slots in a shulker box, in same order as in box
_getBoxAtPos(pos) -> (
    return(filter(map(range(27), get(inventory_get(pos, _), 0)), _));
);

//Get color of shulker box at position
//
//@param pos position of box
//@return color of box, null if block is not shulker box, empty string if box is not colored
_getBoxColor(pos) -> (
    block = block(pos);
    if(replace_first(block, '.*_shulker_box') == '',
        return(replace(block, '_shulker_box')),
        if(block == 'shulker_box', return(''), return(null))
    );
);

//Get name of shulker box at position
//
//@param pos position of box
//@return name of box, null if box was not renamed (i.e. nbt data does not contain name)
_getBoxName(pos) -> (
    //Remove all nbt data except box names
    before = '\\{CustomName:' + global_quote + '\\{"text":"';
    after = '"}' + global_quote + ',Items:.*';
    nbt = block_data(pos);
    replaceBefore = replace(nbt, before);
    if(replaceBefore == nbt,
        return(null),
        return(replace(replaceBefore, after))
    );
);


//Main

//Import item categories from file and give them to player in shulker boxes.
//
//@param file file name, file has to be in JSON format and contain at least
//  the "item" object, which should contain a 2-dimensional array containing
//  item names following this structure: [[items in category 1], [items in category 2], [items in category 3], ...].
//  The "categories" and "colors" objects are optional and should each contain
//  a 1-dimensional array containing the category names and colors,
//  matching the order present in the "items" object, so:
//  [name of category 1, name of category 2, name of category 3, ...]
//  [color of category 1, color of category 2, color of category 3, ...]
//  Uncolored boxes are represented as "" in the "colors" object.
//@param container type to put items in, if this is not shulker_box then colors are ignored
//@param fillLevel maximum fill level of shulker boxes, if number of items in
//  category exceed fill level, the category items are split into the according
//  number of shulker boxes. Numbers are added to category/box names in this case.
//@param itemCount number of items per slot (i.e. stack size)
//@param proportionalStackSize if true, 16 stackables get same relative stack size as 64 stackables,
//  so if itemCount is 32, 16 stackables will get 8 (so 16 * 32 / 64) items. Useful if output signal strength matters.
//  If false, 16 stackables always get 16 items if itemCount is higher than 16.
importCategories(file, container, fillLevel, itemCount, proportionalStackSize) -> (
  input = try(read_file(global_path + file, 'shared_json'), 'exception', _error('Error while reading file'));
  cNames = get(input, 'categories');
  cItems = get(input, 'items');
  cColors = get(input, 'colors');
  if (!cItems, _error('File does not contain "items" object or "items" object is empty'));
  if (!all(cItems, get(_, 0)), _error('File contains "items" object with empty categories or invalid structure'));
  if (!cNames, _warning('File does not contain "categories" object or "categories" object is empty, default names were used'));
  if (container == 'shulker_box' && !cColors, _warning('File does not contain "colors" object or "colors" object is empty, unable to color shulker boxes'));

  //Iterate over item category names
  c_for(n = 0, n < length(cItems), n+=1,
    thisCategory = get(cItems, n);
    numItems = length(thisCategory);
    boxID = if(container == 'shulker_box' && get(cColors, n) && n < length(cColors), //Also check length because get() wraps back to beginning on out of bounds access
        str('%s_shulker_box', get(cColors, n)),
        container
    );
    boxTitle = if(get(cNames, n), get(cNames, n), 'Category ' + (n+1));

    //Fill apporpirate amount of boxes based on desired fill level
    c_for(i = 0, i * fillLevel < numItems, i+=1,
      boxItems = slice(thisCategory, i * fillLevel, min((i + 1) * fillLevel, numItems));
      boxContent = map(boxItems, {
        'id' -> _,
        'Count' -> if(proportionalStackSize,
            ceil(stack_limit(_) * itemCount / 64), //16 stackables get same relative item count as 64 stackables
            min(stack_limit(_), itemCount)), //16 stackables get 16 items if itemCount is higher than 16
        'Slot' -> _i});

      //Summon shulker box at player position
      numBoxTitle = if(i == 0, boxTitle, boxTitle + ' ' + (i+1));
      spawn('item', pos(player()), {
        'Item' -> {
            'id' -> boxID,
            'Count' -> 1,
            'tag' -> {
            'BlockEntityTag' -> {'Items' -> boxContent},
            'display' -> {'Name' -> global_quote + '{"text":"' + numBoxTitle + '"}' + global_quote}
            }
        },
        'PickupDelay' -> 6 //Prevents player from picking up boxes before they fall down
      });
    );
  );
);

//Exports item categories to file. Item categories will be taken from shulker boxes
//positioned on a selected row of blocks in the world. The start of a new category is
//determined by the differnce in color of the boxes, so a single category can stretch
//accross multiple boxes and contain more than 27 items. Only once the next box has
//a different color than the previous, a new category is started.
//Other than that, box colors are not tracked, so colors may be reused.
//File will be in JSON format.
//
//@param from_pos starting position
//@param to_pos end position
exportCategories(file, from_pos, to_pos) -> (
    if(length(filter(to_pos - from_pos, _ == 0)) != 2, _error('The area must be a 1x1 row'));

    //Get list containing all box names, contents (in single list, still uncategorized) and colors
    //Contains all positions in player selection
    positions = _scanStrip(from_pos, to_pos);
    boxNames = map(positions, _getBoxName(_));
    boxColors = map(positions, _getBoxColor(_));
    boxItems = map(positions, _getBoxAtPos(_));

    //Separate box contents into categories based on box color,
    //omit names and colors of boxes that aren't the first box of their category
    cNames = [];
    cColors = [];
    cItems = [];
    prevColor = null; //Init as null and not as '' to differentiate from uncolored boxes, which get '' as their color
    loop(length(boxItems),
        name = get(boxNames, _);
        color = get(boxColors, _);
        item = get(boxItems, _);
        if(item,
            if(color != prevColor,
                //Case new category is started (differnt colored box found)
                prevColor = color;
                cNames+=if(name, name, '');
                cColors+=if(color, color, '');
                cItems+=item,
                //Case same category is continued (box has same color as last box)
                put(get(cItems, length(cItems) - 1), null, item, 'extend')
            )
        )
    );

    //Write categories to file
    fileContent = {'categories' -> cNames, 'items' -> cItems, 'colors' -> cColors};
    try(write_file(global_path + file, 'shared_json', fileContent), 'exception', _error('Error while writing to file'));
    print(format('f » ', 'g Categories saved as ', str('#26DE81 %s', file)));
);

//Creates 'shared/categories/' folders if not present yet.
createFolders() -> (
    tmpFile = 'tmp';
    files = list_files(global_path, 'shared_json');
    if(all(files, _ != global_path + tmpFile),
        write_file(global_path + tmpFile, 'shared_json', '');
        delete_file(global_path + tmpFile, 'shared_json')
    );
);
