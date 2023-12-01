package diplom.telegramBotDiplom.service;


import com.vdurmont.emoji.EmojiParser;
import diplom.telegramBotDiplom.config.BotConfig;
import diplom.telegramBotDiplom.model.*;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.implementation.bytecode.Addition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.InputStream;
import java.sql.Timestamp;
import java.util.*;

@Slf4j
@Component
public class TelegramBot extends TelegramLongPollingBot {
    private final OrderService orderService;
    private double totalPrice = 0.0;
    private Map<Long, Double> userTotalPrice = new HashMap<>();


    @Autowired
    private UserRepository userRepository;

    static final String HELP_TEXT = "Цей бот допоможе тобі створити замовлення\n\n" +
            "натискайте на необхідну вам команду:\n\n" +
            "напишіть /start, щоб розпочати\n\n" +
            "напишіть /help, щоб побачити це повідомлення знову";

    static final String ERROR_TEXT = "Error occurred: ";

    public enum BotState {
        START,
        MAIN_MENU,
        SUB_MENU,
        KITCHEN_MENU,
        BAR_MENU,


    }

    private Map<Long, Stack<BotState>> userStateStack = new HashMap<>();
    private Map<Long, BotState> userStates = new HashMap<>();



    private boolean isViewMenuMode = false;
    private boolean isMakeOrderMode = false;

    final BotConfig config;


    public TelegramBot(OrderService orderService, BotConfig config) {
        this.orderService = orderService;
        this.config = config;
        List<BotCommand> listofCommands = new ArrayList<>();
        listofCommands.add(new BotCommand("/start", "розпочати роботу бота"));

        listofCommands.add(new BotCommand("/help", "як використовувати бот"));

        try {
            this.execute(new SetMyCommands(listofCommands, new BotCommandScopeDefault(), null));
        } catch (TelegramApiException e) {
            log.error("Error setting bot's command list: " + e.getMessage());
        }
    }

    @Override
    public String getBotUsername() {
        return config.getBotName();
    }

    @Override
    public String getBotToken() {
        return config.getToken();
    }

    public void onUpdateReceived(Update update) {
        long chatId;
        Integer messageId = null;

        if (update.hasMessage()) {
            chatId = update.getMessage().getChatId();
            messageId = update.getMessage().getMessageId();
        } else if (update.hasCallbackQuery()) {
            chatId = update.getCallbackQuery().getMessage().getChatId();
            messageId = update.getCallbackQuery().getMessage().getMessageId();
        } else {
            return;
        }

        BotState currentState = userStates.getOrDefault(chatId, BotState.START);

        log.info("Chat ID: " + chatId + ", Current State: " + currentState);


        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            log.info("Received message: " + messageText);

            if (messageText.equals("Повернутися назад")) {
                // Отримуємо стек станів користувача
                Stack<BotState> stateStack = userStateStack.get(chatId);
                if (stateStack != null && !stateStack.isEmpty()) {
                    // Видаляємо поточний стан
                    stateStack.pop();
                    // Отримуємо попередній стан
                    BotState previousState = stateStack.isEmpty() ? BotState.MAIN_MENU : stateStack.peek();
                    userStates.put(chatId, previousState);

                    // Обробка логіки повернення назад
                    handleBackButtonPressed(chatId);
                } else {
                    // Якщо стек порожній, переводимо користувача в головне меню
                    userStates.put(chatId, BotState.MAIN_MENU);
                }


                return;
            }
            switch (messageText) {
                case "/start":
                    registerUser(update.getMessage());
                    startCommandReceived(chatId, update.getMessage().getChat().getFirstName());
                    break;

                case "/help":
                    prepareAndSendMessage(chatId, HELP_TEXT);
                    break;

                case "переглянути меню":
                    isViewMenuMode = true;
                    isMakeOrderMode = false;
                    sendSubMenu(chatId);
                    break;

                case "зробити замовлення":
                    isViewMenuMode = false;
                    isMakeOrderMode = true;
                    sendSubMenu(chatId);
                    break;

                case "підписатись на розсилку новин":
                    subscribeToNewsletter(chatId);
                    break;
                case "відписатись від розсилки новин":
                    subscribeToNewsletter(chatId);
                    break;

                case "кухня":
                    handleKitchenMenu(chatId);
                    break;

                case "піцца":
                    if (isViewMenuMode) {
                        handlePizzaMenu(chatId);}
                    else if (isMakeOrderMode) {
                        handlePizzaMenuToOrder(chatId);
                    }
                    break;

                case "доповнення":
                    if (isViewMenuMode) {
                        handleAdditionsMenu(chatId);}
                    else if (isMakeOrderMode) {
                        handleAdditionsToOrder(chatId);
                    }
                    break;

                case "десерти":
                    if (isViewMenuMode) {
                        handleDessertsMenu(chatId);}
                    else if (isMakeOrderMode) {
                         handleDessertMenuToOrder(chatId);
                    }
                    break;

                case "бар":
                    handleBarMenu(chatId);
                    break;

                case "пиво":
                    if (isViewMenuMode) {
                        handleBeerMenu(chatId);}
                    else if (isMakeOrderMode) {
                        handleBeerMenuToOrder(chatId);
                    }
                    break;

                case "чай":
                    if (isViewMenuMode) {
                        handleTeaMenu(chatId);}
                    else if (isMakeOrderMode) {
                         handleTeaMenuToOrder(chatId);
                    }
                    break;

                case "кава":
                    if (isViewMenuMode) {
                        handleCoffeeMenu(chatId);}
                    else if (isMakeOrderMode) {
                         handleCoffeeMenuToOrder(chatId);
                    }
                    break;

                case "солодкі напої":
                    if (isViewMenuMode) {
                        handleSweetDrinksMenu(chatId);}
                    else if (isMakeOrderMode) {
                         handleSweetDrinksMenuToOrder(chatId);
                    }
                    break;

                default:
                    prepareAndSendMessage(chatId, "Вибач, таку команду я не знаю");
            }
        }

        if (update.hasCallbackQuery()) {
            String callbackData = update.getCallbackQuery().getData();
            if (callbackData.startsWith("pizza_")) {
                int pizzaId = Integer.parseInt(callbackData.substring("pizza_".length()));
                handlePizzaSelection(chatId, pizzaId);
                return;
            }
            if (callbackData.startsWith("addition_")) {
                int additionId = Integer.parseInt(callbackData.substring("addition_".length()));
                handleAdditionsSelection(chatId, additionId);
                return;
            }
            if (callbackData.startsWith("dessert_")) {
                int dessertsId = Integer.parseInt(callbackData.substring("dessert_".length()));
                handleDessertSelection(chatId, dessertsId);
                return;
            }
            if (callbackData.startsWith("tea_")) {
                int teaId = Integer.parseInt(callbackData.substring("tea_".length()));
                handleTeaSelection(chatId, teaId);
                return;
            }
            if (callbackData.startsWith("coffee_")) {
                int coffeeId = Integer.parseInt(callbackData.substring("coffee_".length()));
                handleCoffeeSelection(chatId, coffeeId);
                return;
            }
            if (callbackData.startsWith("beer_")) {
                int beerId = Integer.parseInt(callbackData.substring("beer_".length()));
                handleBeerSelection(chatId, beerId);
                return;
            }
            if (callbackData.startsWith("sweetDrinks_")) {
                int sweetDrinksId = Integer.parseInt(callbackData.substring("sweetDrinks_".length()));
                handleSweetDrinksSelection(chatId, sweetDrinksId);
                return;
            }
            if (callbackData.startsWith("pizzaOrder_")) {
                int pizzaId = Integer.parseInt(callbackData.substring("pizzaOrder_".length()));
                handlePizzaSelectionToOrder(chatId,pizzaId, messageId);
                return;
            }

            if (callbackData.startsWith("increase_pizza_")) {
                int pizzaId = Integer.parseInt(callbackData.substring("increase_pizza_".length()));
                handleIncreasePizzaQuantity(chatId, pizzaId, messageId);
                return;
            }

            if (callbackData.startsWith("decrease_pizza_")) {
                int pizzaId = Integer.parseInt(callbackData.substring("decrease_pizza_".length()));
                handleDecreasePizzaQuantity(chatId, pizzaId, messageId);
                return;
            }

            if (callbackData.startsWith("add_to_order_continue_pizza_")) {
                int pizzaId = Integer.parseInt(callbackData.substring("add_to_order_continue_pizza_".length()));
                handleAddToOrderContinueForPizza(chatId, pizzaId, messageId);
                return;
            }

            if (callbackData.startsWith("additionsOrder_")) {
                int additionsId = Integer.parseInt(callbackData.substring("additionsOrder_".length()));
                handleAdditionsSelectionToOrder(chatId,additionsId, messageId);
                return;
            }

            if (callbackData.startsWith("increase_additions_")) {
                int additionsId = Integer.parseInt(callbackData.substring("increase_additions_".length()));
                handleIncreaseAdditionsQuantity(chatId, additionsId, messageId);
                return;
            }

            if (callbackData.startsWith("decrease_additions_")) {
                int additionsId = Integer.parseInt(callbackData.substring("decrease_additions_".length()));
                handleDecreaseAdditionsQuantity(chatId, additionsId, messageId);
                return;
            }

            if (callbackData.startsWith("add_to_order_continue_additions_")) {
                int additionsId = Integer.parseInt(callbackData.substring("add_to_order_continue_additions_".length()));
                handleAddToOrderContinueForAdditions(chatId, additionsId, messageId);
                return;
            }
            if (callbackData.startsWith("dessertsOrder_")) {
                int dessertId = Integer.parseInt(callbackData.substring("dessertsOrder_".length()));
                handleDessertSelectionToOrder(chatId,dessertId, messageId);
                return;
            }

            if (callbackData.startsWith("increase_dessert_")) {
                int dessertId = Integer.parseInt(callbackData.substring("increase_dessert_".length()));
                handleIncreaseDessertQuantity(chatId, dessertId, messageId);
                return;
            }

            if (callbackData.startsWith("decrease_dessert_")) {
                int dessertId = Integer.parseInt(callbackData.substring("decrease_dessert_".length()));
                handleDecreaseDessertQuantity(chatId, dessertId, messageId);
                return;
            }

            if (callbackData.startsWith("add_to_order_continue_dessert_")) {
                int dessertId = Integer.parseInt(callbackData.substring("add_to_order_continue_dessert_".length()));
                handleAddToOrderContinueForDessert(chatId, dessertId, messageId);
                return;
            }
            if (callbackData.startsWith("teaOrder_")) {
                int teaId = Integer.parseInt(callbackData.substring("teaOrder_".length()));
                handleTeaSelectionToOrder(chatId,teaId, messageId);
                return;
            }

            if (callbackData.startsWith("increase_tea_")) {
                int teaId = Integer.parseInt(callbackData.substring("increase_tea_".length()));
                handleIncreaseTeaQuantity(chatId, teaId, messageId);
                return;
            }

            if (callbackData.startsWith("decrease_tea_")) {
                int teaId = Integer.parseInt(callbackData.substring("decrease_tea_".length()));
                handleDecreaseTeaQuantity(chatId, teaId, messageId);
                return;
            }

            if (callbackData.startsWith("add_to_order_continue_tea_")) {
                int teaId = Integer.parseInt(callbackData.substring("add_to_order_continue_tea_".length()));
                handleAddToOrderContinueForTea(chatId, teaId, messageId);
                return;
            }
            if (callbackData.startsWith("coffeeOrder_")) {
                int coffeeId = Integer.parseInt(callbackData.substring("coffeeOrder_".length()));
                handleCoffeeSelectionToOrder(chatId,coffeeId, messageId);
                return;
            }

            if (callbackData.startsWith("increase_coffee_")) {
                int coffeeId = Integer.parseInt(callbackData.substring("increase_coffee_".length()));
                handleIncreaseCoffeeQuantity(chatId, coffeeId, messageId);
                return;
            }

            if (callbackData.startsWith("decrease_coffee_")) {
                int coffeeId = Integer.parseInt(callbackData.substring("decrease_coffee_".length()));
                handleDecreaseCoffeeQuantity(chatId, coffeeId, messageId);
                return;
            }

            if (callbackData.startsWith("add_to_order_continue_coffee_")) {
                int coffeeId = Integer.parseInt(callbackData.substring("add_to_order_continue_coffee_".length()));
                handleAddToOrderContinueForCoffee(chatId, coffeeId, messageId);
                return;
            }
            if (callbackData.startsWith("sweetDrinksOrder_")) {
                int sweetDrinksId = Integer.parseInt(callbackData.substring("sweetDrinksOrder_".length()));
                handleSweetDrinksSelectionToOrder(chatId,sweetDrinksId, messageId);
                return;
            }

            if (callbackData.startsWith("increase_sweetDrinks_")) {
                int sweetDrinksId = Integer.parseInt(callbackData.substring("increase_sweetDrinks_".length()));
                handleIncreaseSweetDrinksQuantity(chatId, sweetDrinksId, messageId);
                return;
            }

            if (callbackData.startsWith("decrease_sweetDrinks_")) {
                int sweetDrinksId = Integer.parseInt(callbackData.substring("decrease_sweetDrinks_".length()));
                handleDecreaseSweetDrinksQuantity(chatId, sweetDrinksId, messageId);
                return;
            }

            if (callbackData.startsWith("add_to_order_continue_sweetDrinks_")) {
                int sweetDrinksId = Integer.parseInt(callbackData.substring("add_to_order_continue_sweetDrinks_".length()));
                handleAddToOrderContinueForSweetDrinks(chatId, sweetDrinksId, messageId);
                return;
            }
            if (callbackData.startsWith("beerOrder_")) {
                int beerId = Integer.parseInt(callbackData.substring("beerOrder_".length()));
                handleBeerSelectionToOrder(chatId,beerId, messageId);
                return;
            }

            if (callbackData.startsWith("increase_beer_")) {
                int beerId = Integer.parseInt(callbackData.substring("increase_beer_".length()));
                handleIncreaseBeerQuantity(chatId, beerId, messageId);
                return;
            }

            if (callbackData.startsWith("decrease_beer_")) {
                int beerId = Integer.parseInt(callbackData.substring("decrease_beer_".length()));
                handleDecreaseBeerQuantity(chatId, beerId, messageId);
                return;
            }

            if (callbackData.startsWith("add_to_order_continue_beer_")) {
                int beerId = Integer.parseInt(callbackData.substring("add_to_order_continue_beer_".length()));
                handleAddToOrderContinueForBeer(chatId, beerId, messageId);
                return;
            }

            if (callbackData.startsWith("order_pay_")) {
                handleToOrderPay(chatId, messageId);
                return;
            }
            if (callbackData.startsWith("continue_")) {
                handleContinue(chatId);
                return;
            }




        }
    }

    private void sendMessageWithKeyboardAndEdit(long chatId, String text, InlineKeyboardMarkup keyboardMarkup, int messageId) {
        EditMessageText message = new EditMessageText();
        message.setChatId(chatId);
        message.setMessageId(messageId);
        message.setText(text);
        message.setReplyMarkup(keyboardMarkup);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    // Оголошення поля для зберігання кількості продукта для кожного користувача
    Map<Long, Map<Integer, Integer>> userPizzaQuantity = new HashMap<>();

    Map<Long, Map<Integer, Integer>> userAdditionsQuantity = new HashMap<>();

    Map<Long, Map<Integer, Integer>> userDessertQuantity = new HashMap<>();

    Map<Long, Map<Integer, Integer>> userTeaQuantity = new HashMap<>();

    Map<Long, Map<Integer, Integer>> userCoffeeQuantity = new HashMap<>();

    Map<Long, Map<Integer, Integer>> userSweetDrinksQuantity = new HashMap<>();

    Map<Long, Map<Integer, Integer>> userBeerQuantity = new HashMap<>();

    //ЗАМОВЛЕННЯ ПІЦЦИ
    private void handlePizzaMenuToOrder(long chatId) {

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Список піц:");
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<Pizza> pizzaMenu = createPizzaMenu();
        for (Pizza pizza : pizzaMenu) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(pizza.getName());
            button.setCallbackData("pizzaOrder_" + pizza.getId());
            List<InlineKeyboardButton> rowInLine = new ArrayList<>();
            rowInLine.add(button);

            rowsInLine.add(rowInLine);
        }
        keyboardMarkup.setKeyboard(rowsInLine);
        message.setReplyMarkup(keyboardMarkup);
        executeMessage(message);
    }

    private void handlePizzaSelectionToOrder(long chatId, int pizzaId, int messageId) {

        Pizza selectedPizza = getPizzaById(pizzaId);
        sendPhoto(chatId, selectedPizza.getImageUrl());
        String pizzaInfo = "Назва: " + selectedPizza.getName() + "\n";
        pizzaInfo += "Опис: " + selectedPizza.getDescription() + "\n";
        pizzaInfo += "Склад: " + selectedPizza.getIngredients() + "\n";
        pizzaInfo += "Вартість: " + selectedPizza.getPrice() + " грн" + "\n";

        double pizzaPrice = selectedPizza.getPrice();

        int quantity = userPizzaQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(pizzaId, 0);
        pizzaInfo += "КІЛЬКІСТЬ: " + quantity + "\n";
        pizzaInfo += "Загальна вартість: " + String.format("%.2f", pizzaPrice * quantity) + " грн" + "\n";


        InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForPizza(pizzaId);

        // Отправляем обновленное сообщение с клавиатурой и messageId
        sendMessageWithKeyboardAndEdit(chatId, pizzaInfo, keyboardMarkup, messageId);
    }


    private InlineKeyboardMarkup createQuantityChangeKeyboardForPizza(int pizzaId) {
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();

        // Рядок з кнопками "+" і "-"
        List<InlineKeyboardButton> plusMinusRow = new ArrayList<>();

        InlineKeyboardButton plusButton = new InlineKeyboardButton("+");
        plusButton.setCallbackData("increase_pizza_" + pizzaId);

        InlineKeyboardButton minusButton = new InlineKeyboardButton("-");
        minusButton.setCallbackData("decrease_pizza_" + pizzaId);

        plusMinusRow.add(plusButton);
        plusMinusRow.add(minusButton);

        // Рядок з кнопкою "Додати до замовлення і продовжити"
        List<InlineKeyboardButton> addToOrderContinueRow = new ArrayList<>();
        InlineKeyboardButton addToOrderContinueButton = new InlineKeyboardButton("Додати до замовлення");
        addToOrderContinueButton.setCallbackData("add_to_order_continue_pizza_" + pizzaId);
        addToOrderContinueRow.add(addToOrderContinueButton);



        // Додайте рядки до списку
        rowsInLine.add(plusMinusRow);
        rowsInLine.add(addToOrderContinueRow);


        keyboardMarkup.setKeyboard(rowsInLine);

        return keyboardMarkup;
    }

    private void handleIncreasePizzaQuantity(long chatId, int pizzaId, int messageId) {
        int currentQuantity = userPizzaQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(pizzaId, 0);

        userPizzaQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .put(pizzaId, currentQuantity + 1);

        updatePizzaMessage(chatId, pizzaId, messageId);
    }

    private void handleDecreasePizzaQuantity(long chatId, int pizzaId, int messageId) {
        int currentQuantity = userPizzaQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(pizzaId, 0);

        if (currentQuantity > 0) {
            userPizzaQuantity
                    .computeIfAbsent(chatId, k -> new HashMap<>())
                    .put(pizzaId, currentQuantity - 1);

            updatePizzaMessage(chatId, pizzaId, messageId);
        }
    }


    private void updatePizzaMessage(long chatId, int pizzaId, int messageId) {
        Pizza selectedPizza = getPizzaById(pizzaId);
        String pizzaInfo = "Назва: " + selectedPizza.getName() + "\n";
        pizzaInfo += "Опис: " + selectedPizza.getDescription() + "\n";
        pizzaInfo += "Склад: " + selectedPizza.getIngredients() + "\n";
        pizzaInfo += "Вартість: " + selectedPizza.getPrice() + " грн" + "\n";

        double pizzaPrice = selectedPizza.getPrice();

        int quantity = userPizzaQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(pizzaId, 0);
        pizzaInfo += "КІЛЬКІСТЬ: " + quantity + "\n";
        pizzaInfo += "Загальна вартість: " + String.format("%.2f", pizzaPrice * quantity) + " грн" + "\n";


        InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForPizza(pizzaId);

        sendMessageWithKeyboardAndEdit(chatId, pizzaInfo, keyboardMarkup, messageId);
    }


    private void handleAddToOrderContinueForPizza(long chatId, int pizzaId, int messageId) {
        Pizza selectedPizza = getPizzaById(pizzaId);
        int quantity = userPizzaQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(pizzaId, 0);

        if (quantity == 0) {
            InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForPizza(pizzaId);
            sendMessageWithKeyboardAndEdit(chatId, "Виберіть кількість більше 0 для додавання до замовлення.", keyboardMarkup, messageId);
            log.info("Користувач вибрав кількість 0 для продукту з ID: {}", pizzaId);
        } else {

            Pizza pizza = new Pizza();
            pizza.setId(selectedPizza.getId());
            pizza.setName(selectedPizza.getName());
            pizza.setPrice(selectedPizza.getPrice() * quantity);
            pizza.setQuantity(quantity);

            orderService.addOrUpdateProductInOrder(chatId, pizza);
            log.info("Додано або оновлено продукт у методі handleAddToOrderContinueForPizza з ID: {} до замовлення користувача з chatId: {}, кількість: {}", pizzaId, chatId, quantity);

            double productPrice = selectedPizza.getPrice() * quantity;

            String confirmationMessage = "Продукт \" " + selectedPizza.getName() + "\" у кількості " + quantity + " був доданий до вашого замовлення.";
            confirmationMessage += "\nЗагальна вартість: " + String.format("%.2f", productPrice) + " грн";

            //editMessage(chatId, messageId, confirmationMessage);
            log.info("Оновлено повідомлення користувача з chatId: {} з підтвердженням додавання продукту з ID: {}", chatId, pizzaId);


            InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
            List<InlineKeyboardButton> addToOrderContinueRow = new ArrayList<>();
            InlineKeyboardButton addToOrderContinueButton = new InlineKeyboardButton("Продовжити замовлення");
            addToOrderContinueButton.setCallbackData("continue_");
            addToOrderContinueRow.add(addToOrderContinueButton);

            // Рядок з кнопкою "Додати до замовлення і сплатити"
            List<InlineKeyboardButton> addToOrderPayRow = new ArrayList<>();
            InlineKeyboardButton addToOrderPayButton = new InlineKeyboardButton("Сплатити");
            addToOrderPayButton.setCallbackData("order_pay_" );
            addToOrderPayRow.add(addToOrderPayButton);

            // Додайте рядки до списку

            rowsInLine.add(addToOrderContinueRow);

            rowsInLine.add(addToOrderPayRow);

            keyboardMarkup.setKeyboard(rowsInLine);
            sendMessageWithKeyboardAndEdit(chatId, confirmationMessage, keyboardMarkup, messageId);

        }
    }

    private void handleToOrderPay(long chatId,  int messageId) {

        UserOrder userOrder = orderService.getUserOrder(chatId);
        List<Product> orderProducts = userOrder.getProducts();

        double totalOrderPrice = orderProducts.stream()
                .mapToDouble(Product::getPrice)
                .sum();

        StringBuilder confirmationMessage = new StringBuilder();
        confirmationMessage.append("Ваше замовлення:\n");
        for (Product orderProduct : orderProducts) {
            confirmationMessage.append("Назва: ").append(orderProduct.getName()).append("\n");
            confirmationMessage.append("Кількість: ").append(orderProduct.getQuantity()).append("\n");
            confirmationMessage.append("Ціна: ").append(orderProduct.getPrice()).append(" грн\n");
        }
        confirmationMessage.append("Загальна вартість замовлення: ").append(String.format("%.2f", totalOrderPrice)).append(" грн\n");

        confirmationMessage.append("Для оплати замовлення перейдіть за цим посиланням: https://send.monobank.ua/6GgF9qVhqn\n");
        confirmationMessage.append("У коментарі вкажіть номер вашого столика!");
        prepareAndSendMessage(chatId, confirmationMessage.toString());
        log.info("Оновлено повідомлення користувача з chatId: {} з підтвердженням додавання продукту та інформацією про оплату", chatId);

    }

    //ЗАМОВЛЕННЯ ДОПОВНЕНЬ
    private void handleAdditionsToOrder(long chatId) {

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Список доповнень:");
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<Additions> additionsMenu = createAdditionsMenu();
        for (Additions additions : additionsMenu) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(additions.getName());
            button.setCallbackData("additionsOrder_" + additions.getId());
            List<InlineKeyboardButton> rowInLine = new ArrayList<>();
            rowInLine.add(button);

            rowsInLine.add(rowInLine);
        }
        keyboardMarkup.setKeyboard(rowsInLine);
        message.setReplyMarkup(keyboardMarkup);
        executeMessage(message);
    }

    private void handleAdditionsSelectionToOrder(long chatId, int additionsId, int messageId) {

        Additions selectedAddition = getAdditionById(additionsId);
        sendPhoto(chatId, selectedAddition.getImageUrl());

        String additionInfo = "Назва: " + selectedAddition.getName() + "\n";
        additionInfo += "Вага: " + selectedAddition.getWeight() +" г"+ "\n";
        additionInfo += "Вартість: " + selectedAddition .getPrice() + " грн" + "\n";

        double additionPrice = selectedAddition.getPrice();

        int quantity = userAdditionsQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(additionsId, 0);
        additionInfo += "КІЛЬКІСТЬ: " + quantity + "\n";
        additionInfo += "Загальна вартість: " + String.format("%.2f", additionPrice * quantity) + " грн" + "\n";


        InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForAdditions(additionsId);

        sendMessageWithKeyboardAndEdit(chatId, additionInfo, keyboardMarkup, messageId);
    }


    private InlineKeyboardMarkup createQuantityChangeKeyboardForAdditions(int additionsId) {
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();

        List<InlineKeyboardButton> plusMinusRow = new ArrayList<>();

        InlineKeyboardButton plusButton = new InlineKeyboardButton("+");
        plusButton.setCallbackData("increase_additions_" + additionsId);

        InlineKeyboardButton minusButton = new InlineKeyboardButton("-");
        minusButton.setCallbackData("decrease_additions_" + additionsId);

        plusMinusRow.add(plusButton);
        plusMinusRow.add(minusButton);

        List<InlineKeyboardButton> addToOrderContinueRow = new ArrayList<>();
        InlineKeyboardButton addToOrderContinueButton = new InlineKeyboardButton("Додати до замовлення");
        addToOrderContinueButton.setCallbackData("add_to_order_continue_additions_" + additionsId);
        addToOrderContinueRow.add(addToOrderContinueButton);

        rowsInLine.add(plusMinusRow);
        rowsInLine.add(addToOrderContinueRow);

        keyboardMarkup.setKeyboard(rowsInLine);

        return keyboardMarkup;
    }

    private void handleIncreaseAdditionsQuantity(long chatId, int additionsId, int messageId) {
        int currentQuantity = userAdditionsQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(additionsId, 0);

        userAdditionsQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .put(additionsId, currentQuantity + 1);

        updateAdditionsMessage(chatId, additionsId, messageId);
    }

    private void handleDecreaseAdditionsQuantity(long chatId, int additionId, int messageId) {
        int currentQuantity = userAdditionsQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(additionId, 0);

        if (currentQuantity > 0) {
            userAdditionsQuantity
                    .computeIfAbsent(chatId, k -> new HashMap<>())
                    .put(additionId, currentQuantity - 1);

            updateAdditionsMessage(chatId, additionId, messageId);
        }
    }


    private void updateAdditionsMessage(long chatId, int additionId, int messageId) {
        Additions selectedAddition = getAdditionById(additionId);
        String additionInfo = "Назва: " + selectedAddition.getName() + "\n";
        additionInfo += "Вага: " + selectedAddition.getWeight() +" г"+ "\n";
        additionInfo += "Вартість: " + selectedAddition.getPrice() + " грн" + "\n";

        double additionPrice = selectedAddition.getPrice();

        int quantity = userAdditionsQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(additionId, 0);
        additionInfo += "КІЛЬКІСТЬ: " + quantity + "\n";
        additionInfo += "Загальна вартість: " + String.format("%.2f", additionPrice * quantity) + " грн" + "\n";


        InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForAdditions(additionId);

        sendMessageWithKeyboardAndEdit(chatId, additionInfo, keyboardMarkup, messageId);
    }

    private void handleContinue(long chatId) {
        prepareAndSendMessage(chatId, "Чудовий вибір! Вибір смачних страв безмежний)");
    }
    private void handleAddToOrderContinueForAdditions(long chatId, int additionId, int messageId) {
        Additions selectedAddition = getAdditionById(additionId);
        int quantity = userAdditionsQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(additionId, 0);

        if (quantity == 0) {
            InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForAdditions(additionId);
            sendMessageWithKeyboardAndEdit(chatId, "Виберіть кількість більше 0 для додавання до замовлення.", keyboardMarkup, messageId);
            log.info("Користувач вибрав кількість 0 для продукту з ID: {}", additionId);
        } else {

            Additions additions = new Additions();
            additions.setId(selectedAddition.getId());
            additions.setName(selectedAddition.getName());
            additions.setPrice(selectedAddition.getPrice() * quantity);
            additions.setQuantity(quantity);

            orderService.addOrUpdateProductInOrder(chatId, additions);
            log.info("Додано або оновлено продукт у методі handleAddToOrderContinueForAdditions з ID: {} до замовлення користувача з chatId: {}, кількість: {}", additionId, chatId, quantity);

            double productPrice = selectedAddition.getPrice() * quantity;

            String confirmationMessage = "Продукт \" " + selectedAddition.getName() + "\" у кількості " + quantity + " був доданий до вашого замовлення.";
            confirmationMessage += "\nЗагальна вартість: " + String.format("%.2f", productPrice) + " грн";

            log.info("Оновлено повідомлення користувача з chatId: {} з підтвердженням додавання продукту з ID: {}", chatId, additionId);


            InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
            List<InlineKeyboardButton> addToOrderContinueRow = new ArrayList<>();
            InlineKeyboardButton addToOrderContinueButton = new InlineKeyboardButton("Продовжити замовлення");
            addToOrderContinueButton.setCallbackData("continue_");
            addToOrderContinueRow.add(addToOrderContinueButton);

            List<InlineKeyboardButton> addToOrderPayRow = new ArrayList<>();
            InlineKeyboardButton addToOrderPayButton = new InlineKeyboardButton("Сплатити");
            addToOrderPayButton.setCallbackData("order_pay_" );
            addToOrderPayRow.add(addToOrderPayButton);

            rowsInLine.add(addToOrderContinueRow);

            rowsInLine.add(addToOrderPayRow);

            keyboardMarkup.setKeyboard(rowsInLine);
            sendMessageWithKeyboardAndEdit(chatId, confirmationMessage, keyboardMarkup, messageId);

        }
    }

//ЗАМОВЛЕННЯ ДЕСЕРТІВ
    private void handleDessertMenuToOrder(long chatId) {

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Список десертів:");
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<Dessert> dessertMenu = createDessertsMenu();
        for (Dessert desserts : dessertMenu) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(desserts.getName());
            button.setCallbackData("dessertsOrder_" + desserts.getId());
            List<InlineKeyboardButton> rowInLine = new ArrayList<>();
            rowInLine.add(button);

            rowsInLine.add(rowInLine);
        }
        keyboardMarkup.setKeyboard(rowsInLine);
        message.setReplyMarkup(keyboardMarkup);
        executeMessage(message);
    }

    private void handleDessertSelectionToOrder(long chatId, int dessertId, int messageId) {

        Dessert selectedDessert = getDessertById(dessertId);
        sendPhoto(chatId, selectedDessert.getImageUrl());

        String dessertInfo = "Назва: " + selectedDessert.getName() + "\n";
        dessertInfo += "Опис: " + selectedDessert.getDescription() +"\n";
        dessertInfo += "Вага: " + selectedDessert.getWeight() +" г"+ "\n";
        dessertInfo += "Вартість: " + selectedDessert.getPrice() + " грн" + "\n";

        double additionPrice = selectedDessert.getPrice();

        int quantity = userAdditionsQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(dessertId, 0);
        dessertInfo += "КІЛЬКІСТЬ: " + quantity + "\n";
        dessertInfo += "Загальна вартість: " + String.format("%.2f", additionPrice * quantity) + " грн" + "\n";


        InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForDessert(dessertId);

        sendMessageWithKeyboardAndEdit(chatId, dessertInfo, keyboardMarkup, messageId);
    }


    private InlineKeyboardMarkup createQuantityChangeKeyboardForDessert(int dessertId) {
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();

        List<InlineKeyboardButton> plusMinusRow = new ArrayList<>();

        InlineKeyboardButton plusButton = new InlineKeyboardButton("+");
        plusButton.setCallbackData("increase_dessert_" + dessertId);

        InlineKeyboardButton minusButton = new InlineKeyboardButton("-");
        minusButton.setCallbackData("decrease_dessert_" + dessertId);

        plusMinusRow.add(plusButton);
        plusMinusRow.add(minusButton);

        List<InlineKeyboardButton> addToOrderContinueRow = new ArrayList<>();
        InlineKeyboardButton addToOrderContinueButton = new InlineKeyboardButton("Додати до замовлення");
        addToOrderContinueButton.setCallbackData("add_to_order_continue_dessert_" + dessertId);
        addToOrderContinueRow.add(addToOrderContinueButton);

        rowsInLine.add(plusMinusRow);
        rowsInLine.add(addToOrderContinueRow);

        keyboardMarkup.setKeyboard(rowsInLine);

        return keyboardMarkup;
    }

    private void handleIncreaseDessertQuantity(long chatId, int dessertId, int messageId) {
        int currentQuantity = userDessertQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(dessertId, 0);

        userDessertQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .put(dessertId, currentQuantity + 1);

        updateDessertMessage(chatId, dessertId, messageId);
    }

    private void handleDecreaseDessertQuantity(long chatId, int dessertId, int messageId) {
        int currentQuantity = userDessertQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(dessertId, 0);

        if (currentQuantity > 0) {
            userDessertQuantity
                    .computeIfAbsent(chatId, k -> new HashMap<>())
                    .put(dessertId, currentQuantity - 1);

            updateDessertMessage(chatId, dessertId, messageId);
        }
    }


    private void updateDessertMessage(long chatId, int dessertId, int messageId) {
        Dessert selectedDessert = getDessertById(dessertId);
        String dessertInfo = "Назва: " + selectedDessert.getName() + "\n";
        dessertInfo += "Опис: " + selectedDessert.getDescription() +"\n";
        dessertInfo += "Вага: " + selectedDessert.getWeight() +" г"+ "\n";
        dessertInfo += "Вартість: " + selectedDessert.getPrice() + " грн" + "\n";

        double dessertPrice = selectedDessert.getPrice();

        int quantity = userDessertQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(dessertId, 0);
        dessertInfo += "КІЛЬКІСТЬ: " + quantity + "\n";
        dessertInfo += "Загальна вартість: " + String.format("%.2f", dessertPrice * quantity) + " грн" + "\n";


        InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForDessert(dessertId);

        sendMessageWithKeyboardAndEdit(chatId, dessertInfo, keyboardMarkup, messageId);
    }

    private void handleAddToOrderContinueForDessert(long chatId, int dessertId, int messageId) {
        Dessert selectedDessert = getDessertById(dessertId);
        int quantity = userDessertQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(dessertId, 0);

        if (quantity == 0) {
            InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForDessert(dessertId);
            sendMessageWithKeyboardAndEdit(chatId, "Виберіть кількість більше 0 для додавання до замовлення.", keyboardMarkup, messageId);
            log.info("Користувач вибрав кількість 0 для продукту з ID: {}", dessertId);
        } else {

            Dessert desserts= new Dessert();
            desserts.setId(selectedDessert.getId());
            desserts.setName(selectedDessert.getName());
            desserts.setPrice(selectedDessert.getPrice() * quantity);
            desserts.setQuantity(quantity);

            orderService.addOrUpdateProductInOrder(chatId, desserts);
            log.info("Додано або оновлено продукт у методі handleAddToOrderContinueForDessert з ID: {} до замовлення користувача з chatId: {}, кількість: {}", dessertId, chatId, quantity);

            double productPrice = selectedDessert.getPrice() * quantity;

            String confirmationMessage = "Продукт \" " + selectedDessert.getName() + "\" у кількості " + quantity + " був доданий до вашого замовлення.";
            confirmationMessage += "\nЗагальна вартість: " + String.format("%.2f", productPrice) + " грн";

            log.info("Оновлено повідомлення користувача з chatId: {} з підтвердженням додавання продукту з ID: {}", chatId, dessertId);


            InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
            List<InlineKeyboardButton> addToOrderContinueRow = new ArrayList<>();
            InlineKeyboardButton addToOrderContinueButton = new InlineKeyboardButton("Продовжити замовлення");
            addToOrderContinueButton.setCallbackData("continue_");
            addToOrderContinueRow.add(addToOrderContinueButton);

            List<InlineKeyboardButton> addToOrderPayRow = new ArrayList<>();
            InlineKeyboardButton addToOrderPayButton = new InlineKeyboardButton("Сплатити");
            addToOrderPayButton.setCallbackData("order_pay_" );
            addToOrderPayRow.add(addToOrderPayButton);

            rowsInLine.add(addToOrderContinueRow);

            rowsInLine.add(addToOrderPayRow);

            keyboardMarkup.setKeyboard(rowsInLine);
            sendMessageWithKeyboardAndEdit(chatId, confirmationMessage, keyboardMarkup, messageId);

        }
    }


    //ЗАМОВЛЕННЯ ЧАЮ
    private void handleTeaMenuToOrder(long chatId) {

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Список чаїв:");
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<Tea> teaMenu = createTeaMenu();
        for (Tea tea : teaMenu) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(tea.getName());
            button.setCallbackData("teaOrder_" + tea.getId());
            List<InlineKeyboardButton> rowInLine = new ArrayList<>();
            rowInLine.add(button);

            rowsInLine.add(rowInLine);
        }
        keyboardMarkup.setKeyboard(rowsInLine);
        message.setReplyMarkup(keyboardMarkup);
        executeMessage(message);
    }

    private void handleTeaSelectionToOrder(long chatId, int teaId, int messageId) {

        Tea selectedTea = getTeaById(teaId);


        String teaInfo = "Назва: " + selectedTea.getName() + "\n";
        teaInfo += "Об'єм: " + selectedTea.getMilliliters() + " мл"+ "\n";
        teaInfo += "Вартість: " + selectedTea.getPrice() + " грн"+"\n";

        double teaPrice = selectedTea.getPrice();

        int quantity = userTeaQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(teaId, 0);
        teaInfo += "КІЛЬКІСТЬ: " + quantity + "\n";
        teaInfo += "Загальна вартість: " + String.format("%.2f", teaPrice * quantity) + " грн" + "\n";


        InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForTea(teaId);

        sendMessageWithKeyboardAndEdit(chatId, teaInfo, keyboardMarkup, messageId);
    }


    private InlineKeyboardMarkup createQuantityChangeKeyboardForTea(int teaId) {
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();

        List<InlineKeyboardButton> plusMinusRow = new ArrayList<>();

        InlineKeyboardButton plusButton = new InlineKeyboardButton("+");
        plusButton.setCallbackData("increase_tea_" + teaId);

        InlineKeyboardButton minusButton = new InlineKeyboardButton("-");
        minusButton.setCallbackData("decrease_tea_" + teaId);

        plusMinusRow.add(plusButton);
        plusMinusRow.add(minusButton);

        List<InlineKeyboardButton> addToOrderContinueRow = new ArrayList<>();
        InlineKeyboardButton addToOrderContinueButton = new InlineKeyboardButton("Додати до замовлення");
        addToOrderContinueButton.setCallbackData("add_to_order_continue_tea_" + teaId);
        addToOrderContinueRow.add(addToOrderContinueButton);

        rowsInLine.add(plusMinusRow);
        rowsInLine.add(addToOrderContinueRow);

        keyboardMarkup.setKeyboard(rowsInLine);

        return keyboardMarkup;
    }

    private void handleIncreaseTeaQuantity(long chatId, int teaId, int messageId) {
        int currentQuantity = userTeaQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(teaId, 0);

        userTeaQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .put(teaId, currentQuantity + 1);

        updateTeaMessage(chatId, teaId, messageId);
    }

    private void handleDecreaseTeaQuantity(long chatId, int teaId, int messageId) {
        int currentQuantity = userTeaQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(teaId, 0);

        if (currentQuantity > 0) {
            userTeaQuantity
                    .computeIfAbsent(chatId, k -> new HashMap<>())
                    .put(teaId, currentQuantity - 1);

            updateTeaMessage(chatId, teaId, messageId);
        }
    }


    private void updateTeaMessage(long chatId, int teaId, int messageId) {
        Tea selectedTea = getTeaById(teaId);
        String teaInfo = "Назва: " + selectedTea.getName() + "\n";
        teaInfo += "Об'єм: " + selectedTea.getMilliliters() + " мл"+ "\n";
        teaInfo += "Вартість: " + selectedTea.getPrice() + " грн"+"\n";

        double teaPrice = selectedTea.getPrice();

        int quantity = userTeaQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(teaId, 0);
        teaInfo += "КІЛЬКІСТЬ: " + quantity + "\n";
        teaInfo += "Загальна вартість: " + String.format("%.2f", teaPrice * quantity) + " грн" + "\n";


        InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForTea(teaId);

        sendMessageWithKeyboardAndEdit(chatId, teaInfo, keyboardMarkup, messageId);
    }

    private void handleAddToOrderContinueForTea(long chatId, int teaId, int messageId) {
        Tea selectedTea = getTeaById(teaId);
        int quantity = userTeaQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(teaId, 0);

        if (quantity == 0) {
            InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForTea(teaId);
            sendMessageWithKeyboardAndEdit(chatId, "Виберіть кількість більше 0 для додавання до замовлення.", keyboardMarkup, messageId);
            log.info("Користувач вибрав кількість 0 для продукту з ID: {}", teaId);
        } else {

            Tea tea = new Tea();
            tea.setId(selectedTea.getId());
            tea.setName(selectedTea.getName());
            tea.setPrice(selectedTea.getPrice() * quantity);
            tea.setQuantity(quantity);

            orderService.addOrUpdateProductInOrder(chatId, tea);
            log.info("Додано або оновлено продукт у методі handleAddToOrderContinueForTea з ID: {} до замовлення користувача з chatId: {}, кількість: {}", teaId, chatId, quantity);

            double productPrice = selectedTea.getPrice() * quantity;

            String confirmationMessage = "Продукт \" " + selectedTea.getName() + "\" у кількості " + quantity + " був доданий до вашого замовлення.";
            confirmationMessage += "\nЗагальна вартість: " + String.format("%.2f", productPrice) + " грн";

            log.info("Оновлено повідомлення користувача з chatId: {} з підтвердженням додавання продукту з ID: {}", chatId, teaId);


            InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
            List<InlineKeyboardButton> addToOrderContinueRow = new ArrayList<>();
            InlineKeyboardButton addToOrderContinueButton = new InlineKeyboardButton("Продовжити замовлення");
            addToOrderContinueButton.setCallbackData("continue_");
            addToOrderContinueRow.add(addToOrderContinueButton);

            List<InlineKeyboardButton> addToOrderPayRow = new ArrayList<>();
            InlineKeyboardButton addToOrderPayButton = new InlineKeyboardButton("Сплатити");
            addToOrderPayButton.setCallbackData("order_pay_" );
            addToOrderPayRow.add(addToOrderPayButton);

            rowsInLine.add(addToOrderContinueRow);

            rowsInLine.add(addToOrderPayRow);

            keyboardMarkup.setKeyboard(rowsInLine);
            sendMessageWithKeyboardAndEdit(chatId, confirmationMessage, keyboardMarkup, messageId);

        }
    }


    //ЗАМОВЛЕННЯ КАВИ
    private void handleCoffeeMenuToOrder(long chatId) {

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Список кави:");
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<Coffee> coffeeMenu = createCoffeeMenu();
        for (Coffee coffee : coffeeMenu) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(coffee.getName());
            button.setCallbackData("coffeeOrder_" + coffee.getId());
            List<InlineKeyboardButton> rowInLine = new ArrayList<>();
            rowInLine.add(button);

            rowsInLine.add(rowInLine);
        }
        keyboardMarkup.setKeyboard(rowsInLine);
        message.setReplyMarkup(keyboardMarkup);
        executeMessage(message);
    }

    private void handleCoffeeSelectionToOrder(long chatId, int coffeeId, int messageId) {

        Coffee selectedCoffee = getCoffeeById(coffeeId);


        String coffeeInfo = "Назва: " + selectedCoffee.getName() + "\n";
        coffeeInfo += "Об'єм: " + selectedCoffee.getMilliliters() + " мл"+ "\n";
        coffeeInfo += "Вартість: " + selectedCoffee.getPrice() + " грн"+"\n";

        double coffeePrice = selectedCoffee.getPrice();

        int quantity = userCoffeeQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(coffeeId, 0);
        coffeeInfo += "КІЛЬКІСТЬ: " + quantity + "\n";
        coffeeInfo += "Загальна вартість: " + String.format("%.2f", coffeePrice * quantity) + " грн" + "\n";


        InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForCoffee(coffeeId);

        sendMessageWithKeyboardAndEdit(chatId, coffeeInfo, keyboardMarkup, messageId);
    }


    private InlineKeyboardMarkup createQuantityChangeKeyboardForCoffee(int coffeeId) {
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();

        List<InlineKeyboardButton> plusMinusRow = new ArrayList<>();

        InlineKeyboardButton plusButton = new InlineKeyboardButton("+");
        plusButton.setCallbackData("increase_coffee_" + coffeeId);

        InlineKeyboardButton minusButton = new InlineKeyboardButton("-");
        minusButton.setCallbackData("decrease_coffee_" + coffeeId);

        plusMinusRow.add(plusButton);
        plusMinusRow.add(minusButton);

        List<InlineKeyboardButton> addToOrderContinueRow = new ArrayList<>();
        InlineKeyboardButton addToOrderContinueButton = new InlineKeyboardButton("Додати до замовлення");
        addToOrderContinueButton.setCallbackData("add_to_order_continue_coffee_" + coffeeId);
        addToOrderContinueRow.add(addToOrderContinueButton);

        rowsInLine.add(plusMinusRow);
        rowsInLine.add(addToOrderContinueRow);

        keyboardMarkup.setKeyboard(rowsInLine);

        return keyboardMarkup;
    }

    private void handleIncreaseCoffeeQuantity(long chatId, int coffeeId, int messageId) {
        int currentQuantity = userCoffeeQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(coffeeId, 0);

        userCoffeeQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .put(coffeeId, currentQuantity + 1);

        updateCoffeeMessage(chatId, coffeeId, messageId);
    }

    private void handleDecreaseCoffeeQuantity(long chatId, int coffeeId, int messageId) {
        int currentQuantity = userCoffeeQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(coffeeId, 0);

        if (currentQuantity > 0) {
            userCoffeeQuantity
                    .computeIfAbsent(chatId, k -> new HashMap<>())
                    .put(coffeeId, currentQuantity - 1);

            updateCoffeeMessage(chatId, coffeeId, messageId);
        }
    }


    private void updateCoffeeMessage(long chatId, int coffeeId, int messageId) {
        Coffee selectedCoffee = getCoffeeById(coffeeId);
        String coffeeInfo = "Назва: " + selectedCoffee.getName() + "\n";
        coffeeInfo += "Об'єм: " + selectedCoffee.getMilliliters() + " мл"+ "\n";
        coffeeInfo += "Вартість: " + selectedCoffee.getPrice() + " грн"+"\n";

        double coffeePrice = selectedCoffee.getPrice();

        int quantity = userCoffeeQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(coffeeId, 0);
        coffeeInfo += "КІЛЬКІСТЬ: " + quantity + "\n";
        coffeeInfo += "Загальна вартість: " + String.format("%.2f", coffeePrice * quantity) + " грн" + "\n";


        InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForCoffee(coffeeId);

        sendMessageWithKeyboardAndEdit(chatId, coffeeInfo, keyboardMarkup, messageId);
    }

    private void handleAddToOrderContinueForCoffee(long chatId, int coffeeId, int messageId) {
        Coffee selectedCoffee = getCoffeeById(coffeeId);
        int quantity = userCoffeeQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(coffeeId, 0);

        if (quantity == 0) {
            InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForCoffee(coffeeId);
            sendMessageWithKeyboardAndEdit(chatId, "Виберіть кількість більше 0 для додавання до замовлення.", keyboardMarkup, messageId);
            log.info("Користувач вибрав кількість 0 для продукту з ID: {}", coffeeId);
        } else {

            Coffee coffee = new Coffee();
            coffee.setId(selectedCoffee.getId());
            coffee.setName(selectedCoffee.getName());
            coffee.setPrice(selectedCoffee.getPrice() * quantity);
            coffee.setQuantity(quantity);

            orderService.addOrUpdateProductInOrder(chatId, coffee);
            log.info("Додано або оновлено продукт у методі handleAddToOrderContinueForCoffee з ID: {} до замовлення користувача з chatId: {}, кількість: {}", coffeeId, chatId, quantity);

            double productPrice = selectedCoffee.getPrice() * quantity;

            String confirmationMessage = "Продукт \" " + selectedCoffee.getName() + "\" у кількості " + quantity + " був доданий до вашого замовлення.";
            confirmationMessage += "\nЗагальна вартість: " + String.format("%.2f", productPrice) + " грн";

            log.info("Оновлено повідомлення користувача з chatId: {} з підтвердженням додавання продукту з ID: {}", chatId, coffeeId);


            InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
            List<InlineKeyboardButton> addToOrderContinueRow = new ArrayList<>();
            InlineKeyboardButton addToOrderContinueButton = new InlineKeyboardButton("Продовжити замовлення");
            addToOrderContinueButton.setCallbackData("continue_");
            addToOrderContinueRow.add(addToOrderContinueButton);

            List<InlineKeyboardButton> addToOrderPayRow = new ArrayList<>();
            InlineKeyboardButton addToOrderPayButton = new InlineKeyboardButton("Сплатити");
            addToOrderPayButton.setCallbackData("order_pay_" );
            addToOrderPayRow.add(addToOrderPayButton);

            rowsInLine.add(addToOrderContinueRow);

            rowsInLine.add(addToOrderPayRow);

            keyboardMarkup.setKeyboard(rowsInLine);
            sendMessageWithKeyboardAndEdit(chatId, confirmationMessage, keyboardMarkup, messageId);

        }
    }

    //ЗАМОВЛЕННЯ СОЛОДКИХ НАПОЇВ
    private void handleSweetDrinksMenuToOrder(long chatId) {

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Список солодких напоїв:");
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<SweetDrinks> sweetDrinksMenu = createSweetDrinksMenu();
        for (SweetDrinks sweetDrinks : sweetDrinksMenu) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(sweetDrinks.getName());
            button.setCallbackData("sweetDrinksOrder_" + sweetDrinks.getId());
            List<InlineKeyboardButton> rowInLine = new ArrayList<>();
            rowInLine.add(button);

            rowsInLine.add(rowInLine);
        }
        keyboardMarkup.setKeyboard(rowsInLine);
        message.setReplyMarkup(keyboardMarkup);
        executeMessage(message);
    }

    private void handleSweetDrinksSelectionToOrder(long chatId, int sweetDrinksId, int messageId) {

        SweetDrinks selectedSweetDrinks = getSweetDrinksById(sweetDrinksId);


        String sweetDrinksInfo = "Назва: " + selectedSweetDrinks.getName() + "\n";
        sweetDrinksInfo += "Об'єм: " + selectedSweetDrinks.getMilliliters() + " мл"+ "\n";
        sweetDrinksInfo += "Вартість: " + selectedSweetDrinks.getPrice() + " грн"+"\n";

        double sweetDrinksPrice = selectedSweetDrinks.getPrice();

        int quantity = userSweetDrinksQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(sweetDrinksId, 0);
        sweetDrinksInfo += "КІЛЬКІСТЬ: " + quantity + "\n";
        sweetDrinksInfo += "Загальна вартість: " + String.format("%.2f", sweetDrinksPrice * quantity) + " грн" + "\n";


        InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForSweetDrinks(sweetDrinksId);

        sendMessageWithKeyboardAndEdit(chatId, sweetDrinksInfo, keyboardMarkup, messageId);
    }


    private InlineKeyboardMarkup createQuantityChangeKeyboardForSweetDrinks(int sweetDrinksId) {
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();

        List<InlineKeyboardButton> plusMinusRow = new ArrayList<>();

        InlineKeyboardButton plusButton = new InlineKeyboardButton("+");
        plusButton.setCallbackData("increase_sweetDrinks_" + sweetDrinksId);

        InlineKeyboardButton minusButton = new InlineKeyboardButton("-");
        minusButton.setCallbackData("decrease_sweetDrinks_" + sweetDrinksId);

        plusMinusRow.add(plusButton);
        plusMinusRow.add(minusButton);

        List<InlineKeyboardButton> addToOrderContinueRow = new ArrayList<>();
        InlineKeyboardButton addToOrderContinueButton = new InlineKeyboardButton("Додати до замовлення");
        addToOrderContinueButton.setCallbackData("add_to_order_continue_sweetDrinks_" + sweetDrinksId);
        addToOrderContinueRow.add(addToOrderContinueButton);

        rowsInLine.add(plusMinusRow);
        rowsInLine.add(addToOrderContinueRow);

        keyboardMarkup.setKeyboard(rowsInLine);

        return keyboardMarkup;
    }

    private void handleIncreaseSweetDrinksQuantity(long chatId, int sweetDrinksId, int messageId) {
        int currentQuantity = userSweetDrinksQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(sweetDrinksId, 0);

        userSweetDrinksQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .put(sweetDrinksId, currentQuantity + 1);

        updateSweetDrinksMessage(chatId, sweetDrinksId, messageId);
    }

    private void handleDecreaseSweetDrinksQuantity(long chatId, int sweetDrinksId, int messageId) {
        int currentQuantity = userSweetDrinksQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(sweetDrinksId, 0);

        if (currentQuantity > 0) {
            userSweetDrinksQuantity
                    .computeIfAbsent(chatId, k -> new HashMap<>())
                    .put(sweetDrinksId, currentQuantity - 1);

            updateSweetDrinksMessage(chatId, sweetDrinksId, messageId);
        }
    }


    private void updateSweetDrinksMessage(long chatId, int sweetDrinksId, int messageId) {
        SweetDrinks selectedSweetDrinks = getSweetDrinksById(sweetDrinksId);
        String sweetDrinksInfo = "Назва: " + selectedSweetDrinks.getName() + "\n";
        sweetDrinksInfo += "Об'єм: " + selectedSweetDrinks.getMilliliters() + " мл"+ "\n";
        sweetDrinksInfo += "Вартість: " + selectedSweetDrinks.getPrice() + " грн"+"\n";

        double sweetDrinksPrice = selectedSweetDrinks.getPrice();

        int quantity = userSweetDrinksQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(sweetDrinksId, 0);
        sweetDrinksInfo += "КІЛЬКІСТЬ: " + quantity + "\n";
        sweetDrinksInfo += "Загальна вартість: " + String.format("%.2f", sweetDrinksPrice * quantity) + " грн" + "\n";


        InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForSweetDrinks(sweetDrinksId);

        sendMessageWithKeyboardAndEdit(chatId, sweetDrinksInfo, keyboardMarkup, messageId);
    }

    private void handleAddToOrderContinueForSweetDrinks(long chatId, int sweetDrinksId, int messageId) {
        SweetDrinks selectedSweetDrinks = getSweetDrinksById(sweetDrinksId);
        int quantity = userSweetDrinksQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(sweetDrinksId, 0);

        if (quantity == 0) {
            InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForSweetDrinks(sweetDrinksId);
            sendMessageWithKeyboardAndEdit(chatId, "Виберіть кількість більше 0 для додавання до замовлення.", keyboardMarkup, messageId);
            log.info("Користувач вибрав кількість 0 для продукту з ID: {}",sweetDrinksId);
        } else {

            SweetDrinks sweetDrinks = new SweetDrinks();
            sweetDrinks.setId(selectedSweetDrinks.getId());
            sweetDrinks.setName(selectedSweetDrinks.getName());
            sweetDrinks.setPrice(selectedSweetDrinks.getPrice() * quantity);
            sweetDrinks.setQuantity(quantity);

            orderService.addOrUpdateProductInOrder(chatId, sweetDrinks);
            log.info("Додано або оновлено продукт у методі handleAddToOrderContinueForSweetDrinks з ID: {} до замовлення користувача з chatId: {}, кількість: {}", sweetDrinksId, chatId, quantity);

            double productPrice = selectedSweetDrinks.getPrice() * quantity;

            String confirmationMessage = "Продукт \" " + selectedSweetDrinks.getName() + "\" у кількості " + quantity + " був доданий до вашого замовлення.";
            confirmationMessage += "\nЗагальна вартість: " + String.format("%.2f", productPrice) + " грн";

            log.info("Оновлено повідомлення користувача з chatId: {} з підтвердженням додавання продукту з ID: {}", chatId, sweetDrinksId);


            InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
            List<InlineKeyboardButton> addToOrderContinueRow = new ArrayList<>();
            InlineKeyboardButton addToOrderContinueButton = new InlineKeyboardButton("Продовжити замовлення");
            addToOrderContinueButton.setCallbackData("continue_");
            addToOrderContinueRow.add(addToOrderContinueButton);

            List<InlineKeyboardButton> addToOrderPayRow = new ArrayList<>();
            InlineKeyboardButton addToOrderPayButton = new InlineKeyboardButton("Сплатити");
            addToOrderPayButton.setCallbackData("order_pay_" );
            addToOrderPayRow.add(addToOrderPayButton);

            rowsInLine.add(addToOrderContinueRow);

            rowsInLine.add(addToOrderPayRow);

            keyboardMarkup.setKeyboard(rowsInLine);
            sendMessageWithKeyboardAndEdit(chatId, confirmationMessage, keyboardMarkup, messageId);

        }
    }


    //ЗАМОВЛЕННЯ ПИВА
    private void handleBeerMenuToOrder(long chatId) {

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Список пива:");
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<Beer> beerMenu = createBeerMenu();
        for (Beer beer : beerMenu) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(beer.getName());
            button.setCallbackData("beerOrder_" + beer.getId());
            List<InlineKeyboardButton> rowInLine = new ArrayList<>();
            rowInLine.add(button);

            rowsInLine.add(rowInLine);
        }
        keyboardMarkup.setKeyboard(rowsInLine);
        message.setReplyMarkup(keyboardMarkup);
        executeMessage(message);
    }

    private void handleBeerSelectionToOrder(long chatId, int beerId, int messageId) {

        Beer selectedBeer = getBeerById(beerId);


        String beerInfo = "Назва: " + selectedBeer.getName() + "\n";
        beerInfo += "Об'єм: " + selectedBeer.getMilliliters() + " мл"+ "\n";
        beerInfo += "Вартість: " + selectedBeer.getPrice() + " грн"+"\n";

        double beerPrice = selectedBeer.getPrice();

        int quantity = userBeerQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(beerId, 0);
        beerInfo += "КІЛЬКІСТЬ: " + quantity + "\n";
        beerInfo += "Загальна вартість: " + String.format("%.2f", beerPrice * quantity) + " грн" + "\n";


        InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForBeer(beerId);

        sendMessageWithKeyboardAndEdit(chatId, beerInfo, keyboardMarkup, messageId);
    }


    private InlineKeyboardMarkup createQuantityChangeKeyboardForBeer(int beerId) {
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();

        List<InlineKeyboardButton> plusMinusRow = new ArrayList<>();

        InlineKeyboardButton plusButton = new InlineKeyboardButton("+");
        plusButton.setCallbackData("increase_beer_" + beerId);

        InlineKeyboardButton minusButton = new InlineKeyboardButton("-");
        minusButton.setCallbackData("decrease_beer_" + beerId);

        plusMinusRow.add(plusButton);
        plusMinusRow.add(minusButton);

        List<InlineKeyboardButton> addToOrderContinueRow = new ArrayList<>();
        InlineKeyboardButton addToOrderContinueButton = new InlineKeyboardButton("Додати до замовлення");
        addToOrderContinueButton.setCallbackData("add_to_order_continue_beer_" + beerId);
        addToOrderContinueRow.add(addToOrderContinueButton);

        rowsInLine.add(plusMinusRow);
        rowsInLine.add(addToOrderContinueRow);

        keyboardMarkup.setKeyboard(rowsInLine);

        return keyboardMarkup;
    }

    private void handleIncreaseBeerQuantity(long chatId, int beerId, int messageId) {
        int currentQuantity = userBeerQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(beerId, 0);

        userBeerQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .put(beerId, currentQuantity + 1);

        updateBeerMessage(chatId, beerId, messageId);
    }

    private void handleDecreaseBeerQuantity(long chatId, int beerId, int messageId) {
        int currentQuantity = userBeerQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(beerId, 0);

        if (currentQuantity > 0) {
            userBeerQuantity
                    .computeIfAbsent(chatId, k -> new HashMap<>())
                    .put(beerId, currentQuantity - 1);

            updateBeerMessage(chatId, beerId, messageId);
        }
    }


    private void updateBeerMessage(long chatId, int beerId, int messageId) {
        Beer selectedBeer = getBeerById(beerId);
        String beerInfo = "Назва: " + selectedBeer.getName() + "\n";
        beerInfo += "Об'єм: " + selectedBeer.getMilliliters() + " мл"+ "\n";
        beerInfo += "Вартість: " + selectedBeer.getPrice() + " грн"+"\n";

        double beerPrice = selectedBeer.getPrice();

        int quantity = userBeerQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(beerId, 0);
        beerInfo += "КІЛЬКІСТЬ: " + quantity + "\n";
        beerInfo += "Загальна вартість: " + String.format("%.2f", beerPrice * quantity) + " грн" + "\n";


        InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForBeer(beerId);

        sendMessageWithKeyboardAndEdit(chatId, beerInfo, keyboardMarkup, messageId);
    }

    private void handleAddToOrderContinueForBeer(long chatId, int beerId, int messageId) {
        Beer selectedBeer = getBeerById(beerId);
        int quantity = userBeerQuantity
                .computeIfAbsent(chatId, k -> new HashMap<>())
                .getOrDefault(beerId, 0);

        if (quantity == 0) {
            InlineKeyboardMarkup keyboardMarkup = createQuantityChangeKeyboardForBeer(beerId);
            sendMessageWithKeyboardAndEdit(chatId, "Виберіть кількість більше 0 для додавання до замовлення.", keyboardMarkup, messageId);
            log.info("Користувач вибрав кількість 0 для продукту з ID: {}",beerId);
        } else {

            Beer beer = new Beer();
            beer.setId(selectedBeer.getId());
            beer.setName(selectedBeer.getName());
            beer.setPrice(selectedBeer.getPrice() * quantity);
            beer.setQuantity(quantity);

            orderService.addOrUpdateProductInOrder(chatId, beer);
            log.info("Додано або оновлено продукт у методі handleAddToOrderContinueForBeer з ID: {} до замовлення користувача з chatId: {}, кількість: {}", beerId, chatId, quantity);

            double productPrice = selectedBeer.getPrice() * quantity;

            String confirmationMessage = "Продукт \" " + selectedBeer.getName() + "\" у кількості " + quantity + " був доданий до вашого замовлення.";
            confirmationMessage += "\nЗагальна вартість: " + String.format("%.2f", productPrice) + " грн";

            log.info("Оновлено повідомлення користувача з chatId: {} з підтвердженням додавання продукту з ID: {}", chatId, beerId);


            InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
            List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
            List<InlineKeyboardButton> addToOrderContinueRow = new ArrayList<>();
            InlineKeyboardButton addToOrderContinueButton = new InlineKeyboardButton("Продовжити замовлення");
            addToOrderContinueButton.setCallbackData("continue_");
            addToOrderContinueRow.add(addToOrderContinueButton);

            List<InlineKeyboardButton> addToOrderPayRow = new ArrayList<>();
            InlineKeyboardButton addToOrderPayButton = new InlineKeyboardButton("Сплатити");
            addToOrderPayButton.setCallbackData("order_pay_" );
            addToOrderPayRow.add(addToOrderPayButton);

            rowsInLine.add(addToOrderContinueRow);

            rowsInLine.add(addToOrderPayRow);

            keyboardMarkup.setKeyboard(rowsInLine);
            sendMessageWithKeyboardAndEdit(chatId, confirmationMessage, keyboardMarkup, messageId);

        }
    }


    //РЕДАГУВАННЯ ВЖЕ НАДІСЛАНОГО ПОВІДОМЛЕННЯ
    private void editMessage(long chatId, int messageId, String text) {
        EditMessageText editMessage = new EditMessageText();
        editMessage.setChatId(chatId);
        editMessage.setMessageId(messageId);
        editMessage.setText(text);
        editMessage.setParseMode(ParseMode.MARKDOWN); // Додайте цей рядок, якщо ви використовуєте Markdown для форматування тексту
        editMessage.enableMarkdown(true); // Додайте цей рядок, якщо ви використовуєте Markdown для форматування тексту
        editMessage.enableHtml(false); // Додайте цей рядок, якщо ви НЕ використовуєте HTML для форматування тексту

        try {
            execute(editMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }






    // ОБРОБКА НАТИСКАННЯ КНОПКИ "НАЗАД"
    private void handleBackButtonPressed(long chatId) {
        // Перевірка наявності стану MAIN_MENU
        if (!userStates.containsKey(chatId)) {
            userStates.put(chatId, BotState.MAIN_MENU);
        }

        BotState currentState = userStates.get(chatId);
        log.info( "Current State: " + currentState);
        System.out.println("Current State: " + currentState);
        Stack<BotState> stateStack = userStateStack.get(chatId);

        if (stateStack != null && !stateStack.isEmpty()) {

            System.out.println("States in the stack: " + stateStack.toString());

            BotState previousState = stateStack.pop();
            System.out.println("previous State 1: " + previousState);

            previousState = stateStack.isEmpty() ? BotState.MAIN_MENU : previousState;
            userStates.put(chatId, previousState);
            System.out.println("Current State: " + currentState);

            log.info(" New State State: " + previousState);
            System.out.println("New State State: " + previousState);

            userStateStack.put(chatId, stateStack);

            switch (previousState) {
                case MAIN_MENU:
                    sendMainMenu(chatId);
                    break;
                case SUB_MENU:
                    sendSubMenu(chatId);
                    break;
                case KITCHEN_MENU:
                    handleKitchenMenu(chatId);
                    break;
                case BAR_MENU:
                    handleBarMenu(chatId);
                    break;

            }
            System.out.println("previous State 2: " + previousState);
        } else {
            userStates.put(chatId, BotState.MAIN_MENU);
            sendMainMenu(chatId);
            log.info("Chat ID: " + chatId + ", New State State: " + BotState.MAIN_MENU);
        }
    }

    // ПІДМЕНЮ
    private void sendSubMenu(long chatId) {

        userStates.put(chatId, BotState.SUB_MENU);

        Stack<BotState> stateStack = userStateStack.computeIfAbsent(chatId, k -> new Stack<>());

        stateStack.push(BotState.SUB_MENU);
        userStateStack.put(chatId, stateStack);

        log.info("Stack after adding to stack in SubMenu: {}", stateStack);

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Оберіть, будь ласка, розділ меню:");
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        List<KeyboardRow> keyboardRows = new ArrayList<>();
        KeyboardRow row = new KeyboardRow();
        row.add("бар");
        row.add("кухня");
        keyboardRows.add(row);
        row = new KeyboardRow();
        row.add("Повернутися назад");
        keyboardRows.add(row);
        keyboardMarkup.setKeyboard(keyboardRows);
        message.setReplyMarkup(keyboardMarkup);
        executeMessage(message);
    }

    //ГОЛОВНЕ МЕНЮ
    private void sendMainMenu(long chatId) {
        userStates.put(chatId, BotState.MAIN_MENU);
        BotState currentState = userStates.getOrDefault(chatId, BotState.START);

        Stack<BotState> stateStack = userStateStack.computeIfAbsent(chatId, k -> new Stack<>());
        stateStack.push(currentState);
        System.out.println("currentState in main menu "+ currentState);

        log.info("Stack after adding to stack: {}", stateStack);
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Оберіть, будь ласка, опцію:");
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        List<KeyboardRow> keyboardRows = new ArrayList<>();
        KeyboardRow row = new KeyboardRow();
        row.add("переглянути меню");
        row.add("зробити замовлення");
        keyboardRows.add(row);

        Optional<User> userOptional = userRepository.findById(chatId);
        boolean subscribedToNews = userOptional.map(User::isSubscribedToNews).orElse(false);

        String subscriptionButtonText = subscribedToNews ? "відписатись від розсилки новин" : "підписатись на розсилку новин";
        row = new KeyboardRow();
        row.add(subscriptionButtonText);
        keyboardRows.add(row);

        keyboardMarkup.setKeyboard(keyboardRows);
        message.setReplyMarkup(keyboardMarkup);
        executeMessage(message);

        log.info("Chat ID: {}, Current subscribed state: {}", chatId, subscribedToNews);
    }

    //МЕНЮ КУХНІ
    private void handleKitchenMenu(long chatId) {

        BotState currentState = userStates.getOrDefault(chatId, BotState.START);

        Stack<BotState> stateStack = userStateStack.computeIfAbsent(chatId, k -> new Stack<>());
        stateStack.push(currentState);

        userStates.put(chatId, BotState.KITCHEN_MENU);
        log.info("Stack after adding to stack: {}", stateStack);
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Оберіть, будь ласка, розділ меню:");
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        List<KeyboardRow> keyboardRows = new ArrayList<>();
        KeyboardRow row = new KeyboardRow();
        row.add("піцца");
        row.add("доповнення");
        row.add("десерти");
        keyboardRows.add(row);
        row = new KeyboardRow();
        row.add("Повернутися назад");
        keyboardRows.add(row);
        keyboardMarkup.setKeyboard(keyboardRows);
        message.setReplyMarkup(keyboardMarkup);
        executeMessage(message);
    }

    //МЕНЮ БАРУ
    private void handleBarMenu(long chatId) {
        userStates.put(chatId, BotState.BAR_MENU);
        BotState currentState = userStates.getOrDefault(chatId, BotState.START);

        Stack<BotState> stateStack = userStateStack.computeIfAbsent(chatId, k -> new Stack<>());
        stateStack.push(currentState);
        log.info("currentState in bar: {}", currentState);
        log.info("Stack after adding to stack in bar: {}", stateStack);

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Оберіть, будь ласка, розділ бару:");
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        List<KeyboardRow> keyboardRows = new ArrayList<>();
        KeyboardRow row = new KeyboardRow();
        row.add("кава");
        row.add("чай");
        row.add("солодкі напої");
        row.add("пиво");
        keyboardRows.add(row);
        row = new KeyboardRow();
        row.add("Повернутися назад");
        keyboardRows.add(row);
        keyboardMarkup.setKeyboard(keyboardRows);
        message.setReplyMarkup(keyboardMarkup);
        executeMessage(message);
    }

    //ПІЦЦА
    private void handlePizzaMenu(long chatId) {

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Список піц:");
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<Pizza> pizzaMenu = createPizzaMenu();
        for (Pizza pizza : pizzaMenu) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(pizza.getName());
            button.setCallbackData("pizza_" + pizza.getId());
            List<InlineKeyboardButton> rowInLine = new ArrayList<>();
            rowInLine.add(button);

            rowsInLine.add(rowInLine);
        }
        keyboardMarkup.setKeyboard(rowsInLine);
        message.setReplyMarkup(keyboardMarkup);
        executeMessage(message);
    }


    private void handlePizzaSelection(long chatId, int pizzaId) {
        Pizza selectedPizza = getPizzaById(pizzaId);
        sendPhoto(chatId, selectedPizza.getImageUrl());
        String pizzaInfo = "Назва: " + selectedPizza.getName() + "\n";
        pizzaInfo += "Опис: " + selectedPizza.getDescription() + "\n";
        pizzaInfo += "Склад: " + selectedPizza.getIngredients() + "\n";
        pizzaInfo += "Вартість: " + selectedPizza.getPrice() + " грн"+"\n";
        prepareAndSendMessage(chatId, pizzaInfo);
    }

    private Pizza getPizzaById(int pizzaId) {
        List<Pizza> pizzaMenu = createPizzaMenu();
        for (Pizza pizza : pizzaMenu) {
            if (pizza.getId() == pizzaId) {
                return pizza;
            }
        }
        return null;
    }

    private List<Pizza> createPizzaMenu() {
        List<Pizza> pizzaMenu = new ArrayList<>();

        Pizza margherita = new Pizza(1, "Піцца Маргарита", "Класична піца з соусом та сиром", "margherita.png", "Сир, томати, соус", 220.0);
        Pizza pepperoni = new Pizza(2, "Піцца Пепероні", "Піца з пепероні та сиром", "pepperoni.jpg", "Пепероні, сир, соус", 200.0);
        Pizza quattroStagioni = new Pizza(3, "Піцца Кватро Стагіоні", "Піца з чотирма начинками", "quattro_stagioni.jpg", "Шинка, гриби, артишоки, оливки, сир, соус", 250.0);
        Pizza hawaiian = new Pizza(4, "Піцца Гавайська", "Піца з ананасами і шинкою", "hawaiian.jpg", "Ананаси, шинка, сир, соус", 190.0);
        Pizza vegetariana = new Pizza(5, "Піцца Вегетаріана", "Вегетаріанська піца з овочами", "vegetariana.png", "Печериці, брокколі, перець, цибуля, сир, соус", 145.0);
        Pizza capricciosa = new Pizza(6, "Піцца Капрічоза", "Піца з грибами і артишоками", "capricciosa.jpg", "Гриби, артишоки, сир, соус", 160.0);
        Pizza quattroFormaggi = new Pizza(7, "Піцца Кватро Формаджі", "Піца з чотирма видами сиру", "quattro_formaggi.jpg", "Пармезан, дор-блю, моцарелла, гауда, соус", 200.0);

        pizzaMenu.add(margherita);
        pizzaMenu.add(pepperoni);
        pizzaMenu.add(quattroStagioni);
        pizzaMenu.add(hawaiian);
        pizzaMenu.add(vegetariana);
        pizzaMenu.add(capricciosa);
        pizzaMenu.add(quattroFormaggi);

        return pizzaMenu;
    }

    //ЧАЙ
    private void handleTeaMenu(long chatId) {

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Список чаю:");
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<Tea> teaMenu = createTeaMenu();
        for (Tea tea : teaMenu) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(tea.getName());
            button.setCallbackData("tea_" + tea.getId());
            List<InlineKeyboardButton> rowInLine = new ArrayList<>();
            rowInLine.add(button);

            rowsInLine.add(rowInLine);
        }
        keyboardMarkup.setKeyboard(rowsInLine);
        message.setReplyMarkup(keyboardMarkup);
        executeMessage(message);
    }
    private void handleTeaSelection(long chatId, int teaId) {
        Tea selectedTea = getTeaById(teaId);
        String teaInfo = "Назва: " + selectedTea.getName() + "\n";
        teaInfo += "Об'єм: " + selectedTea.getMilliliters() + " мл"+ "\n";
        teaInfo += "Вартість: " + selectedTea.getPrice() + " грн"+"\n";
        prepareAndSendMessage(chatId, teaInfo);
    }

    private Tea getTeaById(int teaId) {
        List<Tea> teaMenu = createTeaMenu();
        for (Tea tea : teaMenu) {
            if (tea.getId() == teaId) {
                return tea;
            }
        }
        return null;
    }

    private List<Tea> createTeaMenu() {
        List<Tea> teaMenu = new ArrayList<>();

        Tea tea1 = new Tea(20, "Чай чорний", 250, 15);
        Tea tea2 = new Tea(21, "Чай зелений", 250, 18);
        Tea tea3 = new Tea(22, "Чай фруктовий", 200, 20);
        Tea tea4 = new Tea(23, "Чай трав'яний", 200, 22);

        teaMenu.add(tea1);
        teaMenu.add(tea2);
        teaMenu.add(tea3);
        teaMenu.add(tea4);

        return teaMenu;
    }

    //КАВА
    private void handleCoffeeMenu(long chatId) {

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Список кави:");
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<Coffee> coffeeMenu = createCoffeeMenu();
        for (Coffee coffee : coffeeMenu) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(coffee.getName());
            button.setCallbackData("coffee_" + coffee.getId());
            List<InlineKeyboardButton> rowInLine = new ArrayList<>();
            rowInLine.add(button);

            rowsInLine.add(rowInLine);
        }
        keyboardMarkup.setKeyboard(rowsInLine);
        message.setReplyMarkup(keyboardMarkup);
        executeMessage(message);
    }
    private void handleCoffeeSelection(long chatId, int coffeeId) {
        Coffee selectedCoffee = getCoffeeById(coffeeId);
        String coffeeInfo = "Назва: " + selectedCoffee.getName() + "\n";
        coffeeInfo += "Об'єм: " + selectedCoffee.getMilliliters() + " мл"+ "\n";
        coffeeInfo += "Вартість: " + selectedCoffee.getPrice() + " грн"+"\n";
        prepareAndSendMessage(chatId, coffeeInfo);
    }

    private Coffee getCoffeeById(int coffeeId) {
        List<Coffee> coffeeMenu = createCoffeeMenu();
        for (Coffee coffee : coffeeMenu) {
            if (coffee.getId() == coffeeId) {
                return coffee;
            }
        }
        return null;
    }

    private List <Coffee> createCoffeeMenu() {
        List<Coffee> coffeeMenu = new ArrayList<>();

        Coffee coffee1 = new Coffee(16, "Кава американо", 100, 20);
        Coffee coffee2 = new Coffee(17, "Кава еспресо", 30, 18);
        Coffee coffee3 = new Coffee(18, "Кава капучино", 250, 30);
        Coffee coffee4 = new Coffee(19, "Кава латте", 300, 35);

        coffeeMenu.add(coffee1);
        coffeeMenu.add(coffee2);
        coffeeMenu.add(coffee3);
        coffeeMenu.add(coffee4);

        return coffeeMenu;
    }
    //СОЛОДКІ НАПОЇ
    private void handleSweetDrinksMenu(long chatId) {

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Список солодких напоїв:");
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<SweetDrinks> sweetDrinksMenu = createSweetDrinksMenu();
        for (SweetDrinks sweetDrinks : sweetDrinksMenu) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(sweetDrinks.getName());
            button.setCallbackData("sweetDrinks_" + sweetDrinks.getId());
            List<InlineKeyboardButton> rowInLine = new ArrayList<>();
            rowInLine.add(button);

            rowsInLine.add(rowInLine);
        }
        keyboardMarkup.setKeyboard(rowsInLine);
        message.setReplyMarkup(keyboardMarkup);
        executeMessage(message);
    }
    private void handleSweetDrinksSelection(long chatId, int sweetDrinksId) {
        SweetDrinks selectedSweetDrinks = getSweetDrinksById(sweetDrinksId);
        String sweetDrinksInfo = "Назва: " + selectedSweetDrinks.getName() + "\n";
        sweetDrinksInfo += "Об'єм: " + selectedSweetDrinks.getMilliliters() + " мл"+ "\n";
        sweetDrinksInfo += "Вартість: " + selectedSweetDrinks.getPrice() + " грн"+"\n";
        prepareAndSendMessage(chatId, sweetDrinksInfo);
    }


    private SweetDrinks getSweetDrinksById(int sweetDrinksId) {
        List<SweetDrinks> sweetDrinksMenu = createSweetDrinksMenu();
        for (SweetDrinks sweetDrinks : sweetDrinksMenu) {
            if (sweetDrinks.getId() == sweetDrinksId) {
                return sweetDrinks;
            }
        }
        return null;
    }

    private List <SweetDrinks> createSweetDrinksMenu() {
        List<SweetDrinks> sweetDrinksMenu = new ArrayList<>();

        SweetDrinks sweetDrinks1 = new SweetDrinks(24, "Coca-Cola Zero", 500, 55);
        SweetDrinks sweetDrinks2 = new SweetDrinks(25, "Coca-Cola", 500, 55);
        SweetDrinks sweetDrinks3 = new SweetDrinks(26, "Fanta Апельсин", 500, 55);
        SweetDrinks sweetDrinks4 = new SweetDrinks(27, "Sprite", 500, 55);
        SweetDrinks sweetDrinks5 = new SweetDrinks(28, "Fuzetea Чорний чай зі смаком лимону", 500, 62);
        SweetDrinks sweetDrinks6 = new SweetDrinks(29, "Schweppes Індіан Тонік", 330, 42);
        SweetDrinks sweetDrinks7 = new SweetDrinks(30, "Schweppes Мохіто", 330, 42);
        SweetDrinks sweetDrinks8 = new SweetDrinks(31, "Сік Rich Яблуко", 500, 55);
        SweetDrinks sweetDrinks9 = new SweetDrinks(32, "Сік Rich Апельсин", 500, 55);
        SweetDrinks sweetDrinks10 = new SweetDrinks(33, "Сік Rich Мультифрукт", 500, 55);

        sweetDrinksMenu.add(sweetDrinks1);
        sweetDrinksMenu.add(sweetDrinks2);
        sweetDrinksMenu.add(sweetDrinks3);
        sweetDrinksMenu.add(sweetDrinks4);
        sweetDrinksMenu.add(sweetDrinks5);
        sweetDrinksMenu.add(sweetDrinks6);
        sweetDrinksMenu.add(sweetDrinks7);
        sweetDrinksMenu.add(sweetDrinks8);
        sweetDrinksMenu.add(sweetDrinks9);
        sweetDrinksMenu.add(sweetDrinks10);

        return sweetDrinksMenu;
    }

    //ДОПОВНЕННЯ
    private void handleAdditionsMenu(long chatId) {

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Список доповнень:");
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<Additions> additionsMenu = createAdditionsMenu();
        for (Additions additions : additionsMenu) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(additions.getName());
            button.setCallbackData("addition_" + additions.getId());
            List<InlineKeyboardButton> rowInLine = new ArrayList<>();
            rowInLine.add(button);

            rowsInLine.add(rowInLine);
        }
        keyboardMarkup.setKeyboard(rowsInLine);
        message.setReplyMarkup(keyboardMarkup);
        executeMessage(message);
    }

    private void handleAdditionsSelection(long chatId, int additionId) {
        Additions selectedAdditon = getAdditionById(additionId);
        sendPhoto(chatId, selectedAdditon.getImageUrl());
        String AdditonInfo = "Назва: " + selectedAdditon.getName() + "\n";
        AdditonInfo += "Вага: " + selectedAdditon.getWeight() + " г"+ "\n";
        AdditonInfo += "Вартість: " + selectedAdditon.getPrice() + " грн"+"\n";
        prepareAndSendMessage(chatId, AdditonInfo);
    }

    private Additions getAdditionById(int additionId) {
        List<Additions> additionsMenu = createAdditionsMenu();
        for (Additions addition : additionsMenu) {
            if (addition.getId() == additionId) {
                return addition;
            }
        }
        return null;
    }
    private List<Additions> createAdditionsMenu() {
        List<Additions> additionsMenu = new ArrayList<>();

        Additions ketchup = new Additions(8, "Кетчуп", 20, "ketchup.jpg", 10.0);
        Additions mayo = new Additions(9, "Майонез", 25, "mayo.jpg", 12.0);
        Additions mustard = new Additions(10, "Гірчиця", 18, "mustard.jpg", 8.0);
        Additions garlicSauce = new Additions(11, "Часниковий соус", 22, "garlic_sauce.jpg", 15.0);

        additionsMenu.add(ketchup);
        additionsMenu.add(mayo);
        additionsMenu.add(mustard);
        additionsMenu.add(garlicSauce);

        return additionsMenu;
    }

    //РЕЄСТРАЦІЯ КОРИСТУВАЧА
    private void registerUser(Message msg) {
        long chatId = msg.getChatId();
        Optional<User> userOptional = userRepository.findById(chatId);

        if (userOptional.isEmpty()) {
            User user = new User();
            user.setChatId(chatId);
            var chat = msg.getChat();
            user.setFirstName(chat.getFirstName());
            user.setLastName(chat.getLastName());
            user.setUserName(chat.getUserName());
            user.setRegisteredAt(new Timestamp(System.currentTimeMillis()));
            userRepository.save(user);
            log.info("New user saved: " + user);
        }
    }
    //ПРИВІТАННЯ
    private void startCommandReceived(long chatId, String name) {
        String answer = EmojiParser.parseToUnicode("Вітаємо, " + name + "!" + " :blush:");
        log.info("Replied to user " + name);
        sendMessage(chatId, answer, BotState.MAIN_MENU);
        orderService.clearUserCart(chatId);
        userPizzaQuantity.remove(chatId);
        userAdditionsQuantity.remove(chatId);
        userTeaQuantity.remove(chatId);
        userCoffeeQuantity.remove(chatId);
        userSweetDrinksQuantity.remove(chatId);
        userBeerQuantity.remove(chatId);
    }
    //ШАБЛОН ДЛЯ НАДСИЛАННЯ ПОВІДОМЛЕНЬ
    private void sendMessage(long chatId, String textToSend, BotState nextState) {
        BotState currentState = userStates.getOrDefault(chatId, BotState.MAIN_MENU); // Правильно

        log.info("currentState in sendMessage "+ currentState);
        Stack<BotState> stateStack = userStateStack.computeIfAbsent(chatId, k -> new Stack<>());
        stateStack.push(currentState);

        userStates.put(chatId, nextState);
        log.info("Stack after adding to stack in sendMessage: {}", stateStack+"/n/n");
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(textToSend);
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        List<KeyboardRow> keyboardRows = new ArrayList<>();
        KeyboardRow row = new KeyboardRow();
        row.add("переглянути меню");
        row.add("зробити замовлення");
        keyboardRows.add(row);
        row = new KeyboardRow();
        row.add("підписатись на розсилку новин");
        keyboardRows.add(row);
        keyboardMarkup.setKeyboard(keyboardRows);
        message.setReplyMarkup(keyboardMarkup);
        executeMessage(message);
    }


    //НАДСИЛАННЯ ПОВІДОМЛЕНЬ ЧЕРЕЗ БІБЛІОТЕКУ TELEGRAM API.
    private void executeMessage(SendMessage message) {
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error(ERROR_TEXT + e.getMessage());
        }
    }

    //НАДСИЛАННЯ ПОВІДОМЛЕНЬ
    private void prepareAndSendMessage(long chatId, String textToSend) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(textToSend);
        executeMessage(message);
    }

    //ДЕСЕРТИ
    private void handleDessertsMenu(long chatId) {
//        BotState currentState = userStates.getOrDefault(chatId, BotState.START);
//
//        Stack<BotState> stateStack = userStateStack.computeIfAbsent(chatId, k -> new Stack<>());
//        stateStack.push(currentState);
//
//        userStates.put(chatId, BotState.KITCHEN_MENU);
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Список десертів:");
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<Dessert> dessertsMenu = createDessertsMenu();
        for (Dessert dessert : dessertsMenu) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(dessert.getName());
            button.setCallbackData("dessert_" + dessert.getId());
            List<InlineKeyboardButton> rowInLine = new ArrayList<>();
            rowInLine.add(button);

            rowsInLine.add(rowInLine);
        }
        keyboardMarkup.setKeyboard(rowsInLine);
        message.setReplyMarkup(keyboardMarkup);
        executeMessage(message);
    }

    private void handleDessertSelection(long chatId, int dessertId) {
        Dessert selectedDessert = getDessertById(dessertId);
        sendPhoto(chatId, selectedDessert.getImageUrl());
        String dessertInfo = "Назва: " + selectedDessert.getName() + "\n";
        dessertInfo += "Опис: " + selectedDessert.getDescription() + "\n";
        dessertInfo += "Вага: " + selectedDessert.getWeight() + " г"+"\n";
        dessertInfo += "Вартість: " + selectedDessert.getPrice() + " грн" + "\n";
        prepareAndSendMessage(chatId, dessertInfo);
    }

    private Dessert getDessertById(int dessertId) {
        List<Dessert> dessertsMenu = createDessertsMenu(); // Замініть на свій метод створення десертів
        for (Dessert dessert : dessertsMenu) {
            if (dessert.getId() == dessertId) {
                return dessert;
            }
        }
        return null;
    }

    private List<Dessert> createDessertsMenu() {
        List<Dessert> dessertsMenu = new ArrayList<>();

        Dessert dessert1 = new Dessert(12, "Шоколадний мус", "Ніжний шоколадний мус", 120, "chocolate_mousse.jpg", 50.0);
        Dessert dessert2 = new Dessert(13, "Тірамісу", "Класичний італійський десерт", 150, "tiramisu.jpg", 60.0);
        Dessert dessert3 = new Dessert(14, "Чізкейк", "Найкращий у світі чізкейк", 140, "cheesecake.jpg", 55.0);
        Dessert dessert4 = new Dessert(15, "Фруктова панакотта", "Десерт зі свіжими фруктами", 130, "panna_cotta.jpg", 45.0);

        dessertsMenu.add(dessert1);
        dessertsMenu.add(dessert2);
        dessertsMenu.add(dessert3);
        dessertsMenu.add(dessert4);

        return dessertsMenu;
    }

    //ПИВО
    private void handleBeerMenu(long chatId) {

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Список пиво:");
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInLine = new ArrayList<>();
        List<Beer> beerMenu = createBeerMenu();
        for (Beer beer : beerMenu) {
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(beer.getName());
            button.setCallbackData("beer_" + beer.getId());
            List<InlineKeyboardButton> rowInLine = new ArrayList<>();
            rowInLine.add(button);

            rowsInLine.add(rowInLine);
        }
        keyboardMarkup.setKeyboard(rowsInLine);
        message.setReplyMarkup(keyboardMarkup);
        executeMessage(message);
    }

    private void handleBeerSelection(long chatId, int beerId) {
        Beer selectedBeer = getBeerById(beerId);
        String beerInfo = "Назва: " + selectedBeer.getName() + "\n";
        beerInfo += "Об'єм: " + selectedBeer.getMilliliters() + " мл"+ "\n";
        beerInfo += "Вартість: " + selectedBeer.getPrice() + " грн"+"\n";
        prepareAndSendMessage(chatId, beerInfo);
    }

    private Beer getBeerById(int beerId) {
        List<Beer> beerMenu = createBeerMenu(); // Замініть на свій метод створення десертів
        for (Beer beer : beerMenu) {
            if (beer.getId() == beerId) {
                return beer;
            }
        }
        return null;
    }

    private List<Beer>  createBeerMenu() {
        List<Beer> beerMenu = new ArrayList<>();

        Beer beer1 = new Beer(34, "Stella Artois",  500,  70);
        Beer beer2 = new Beer(35, "Bud",  500, 70);
        Beer beer3 = new Beer(36, "Hoegaarden",  500, 98);
        Beer beer4 = new Beer(37, "ФLeffe Brunе",  500,  98);

        beerMenu.add(beer1);
        beerMenu.add(beer2);
        beerMenu.add(beer3);
        beerMenu.add(beer4);

        return beerMenu;
    }

    //НАДСИЛАННЯ КАРТИНОК
    private void sendPhoto(long chatId, String imageUrl) {
        InputStream is = getClass().getClassLoader().getResourceAsStream(imageUrl);

        SendPhoto message = new SendPhoto();
        message.setChatId(Long.toString(chatId));
        message.setPhoto(new InputFile(is, imageUrl));
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    //ПІДПИСКА АБО ВІДПИСКА
    private void subscribeToNewsletter(long chatId) {
        Optional<User> userOptional = userRepository.findById(chatId);

        if (userOptional.isPresent()) {
            User user = userOptional.get();
            boolean subscribedToNews = user.isSubscribedToNews(); // Поточний статус підписки

            // Змінюємо статус підписки на протилежний
            user.setSubscribedToNews(!subscribedToNews);
            userRepository.save(user);

            if (subscribedToNews) {
                prepareAndSendMessage(chatId, "Ви відписалися від розсилки новин.");
            } else {
                prepareAndSendMessage(chatId, "Ви підписалися на розсилку новин.");
            }

            // Після зміни статусу підписки оновлюємо головне меню
            sendMainMenu(chatId);
        } else {
            // Користувач не знайдений у базі даних
            prepareAndSendMessage(chatId, "Помилка: користувача не знайдено.");
        }
    }

    //РЕКЛАМА ПО ВІВТОРКАХ
    @Scheduled(cron = "0 0 10 * * TUE") // О 10:00 вівторка
    private void sendTuesdayAd() {
        var users = userRepository.findAll();
        var adText = "Кожен вівторок на другу піццу -50%";

        for (User user : users) {
            if (user.isSubscribedToNews()) {
                prepareAndSendMessage(user.getChatId(), adText);
            }
        }
    }
    //РЕКЛАМА ПО ЧЕТВЕРГАХ
    @Scheduled(cron = "0 0 10 * * THU") // О 10:00 четверга
    private void sendThursdayAd() {
        var users = userRepository.findAll();
        var adText = "1+1=3 на піццу";

        for (User user : users) {
            if (user.isSubscribedToNews()) {
                prepareAndSendMessage(user.getChatId(), adText);
            }
        }
    }








}














































