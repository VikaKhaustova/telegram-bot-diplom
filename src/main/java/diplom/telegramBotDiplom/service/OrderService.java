package diplom.telegramBotDiplom.service;

import diplom.telegramBotDiplom.model.Product;
import diplom.telegramBotDiplom.model.UserOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
@Component
@Slf4j
public class OrderService {
    // Використовуйте цей об'єкт для відстеження замовлень користувачів
    private Map<Long, UserOrder> userOrders = new HashMap<>();

    public void addOrUpdateProductInOrder(long userId, Product product) {
        UserOrder userOrder = userOrders.getOrDefault(userId, new UserOrder());

        // Шукаємо продукт за ID в списку замовлення
        Optional<Product> existingProduct = userOrder.getProducts().stream()
                .filter(p -> p.getId() == product.getId())
                .findFirst();

        if (existingProduct.isPresent()) {
            // Якщо продукт вже є в замовленні, оновлюємо його кількість та вартість
            Product productToUpdate = existingProduct.get();
            productToUpdate.setQuantity(product.getQuantity());
            productToUpdate.setPrice(product.getPrice());
        } else {
            // Якщо продукт не знайдено у замовленні, додаємо його
            userOrder.addProduct(product);
        }

        userOrders.put(userId, userOrder);
        log.info("Додано або оновлено продукт:{} у методі addOrUpdateProductInOrder з ID: {} до замовлення користувача з chatId: {}, кількість: {}", product.getName() , product.getId(), userId, product.getQuantity());
    }

    public void clearUserCart(long userId) {
        if (userOrders.containsKey(userId)) {
            userOrders.remove(userId);
            log.info("Замовлення користувача з chatId: {} було очищено", userId);
        } else {
            log.info("Замовлення користувача з chatId: {} відсутнє для очищення", userId);
        }

    }

    public UserOrder getUserOrder(long userId) {
        return userOrders.get(userId);
    }


}

