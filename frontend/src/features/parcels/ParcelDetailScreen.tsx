import { useParams } from "react-router-dom";

export function ParcelDetailScreen() {
  const { id } = useParams();
  return (
    <main>
      <h1>Parcel</h1>
      <p>Parcel id: {id}</p>
    </main>
  );
}
