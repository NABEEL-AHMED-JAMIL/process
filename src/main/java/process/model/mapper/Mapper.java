package process.model.mapper;

public interface Mapper<K, V>{

    public V mapToDto(K k);

    public K mapToEntity(V v);

}
