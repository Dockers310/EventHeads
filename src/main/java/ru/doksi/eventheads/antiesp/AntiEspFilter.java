package ru.doksi.eventheads.antiesp;

/**
 * RU: Фильтр пакетов Anti-ESP без типов PacketEvents в сигнатурах.
 * Главный класс плагина работает только с этим интерфейсом, поэтому EventHeads загружается
 * и без PacketEvents (он объявлен как softdepend): раньше сервер без PacketEvents отклонял
 * плагин целиком с NoClassDefFoundError.
 */
public interface AntiEspFilter {
    void reload();
    void unregister();
}
